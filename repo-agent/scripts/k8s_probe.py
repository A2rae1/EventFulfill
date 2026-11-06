"""Own local namespace only: real Gateway rollout/recovery, quota and CPU HPA lab."""

import asyncio
import json
import time

import httpx
from jobs_init import ROOT

K = ROOT / ".local/tools/k8s/kubectl.exe"
CONFIG = ROOT / ".local/k8s/kubeconfig"


async def command(*args, stdin=None, expect=0):
    p = await asyncio.create_subprocess_exec(
        str(K),
        "--kubeconfig",
        str(CONFIG),
        "--context",
        "kind-internship-lab",
        *args,
        stdin=asyncio.subprocess.PIPE if stdin else None,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    try:
        out, _ = await asyncio.wait_for(p.communicate(stdin.encode() if stdin else None), 150)
    except BaseException:
        p.kill()
        await p.wait()
        raise
    text = out.decode("utf-8", errors="replace")
    if expect == 0 and p.returncode:
        raise RuntimeError(text[:1000])
    return p.returncode, text


async def kubectl(*args):
    return (await command("-n", "internship-lab", *args))[1]


async def pods():
    return json.loads(await kubectl("get", "pods", "-l", "app=event-gateway", "-o", "json"))["items"]


async def main():
    results = {}
    samples = []
    stop = asyncio.Event()
    async with httpx.AsyncClient(timeout=4) as client:
        r = await client.get("http://127.0.0.1:18000/actuator/health")
        r.raise_for_status()
        fallback = await client.get("http://127.0.0.1:18000/api/sessions")
        assert fallback.status_code == 503

        async def monitor():
            while not stop.is_set():
                try:
                    r = await client.get("http://127.0.0.1:18000/actuator/health")
                    samples.append(r.status_code)
                except httpx.HTTPError:
                    samples.append(0)
                await asyncio.sleep(0.15)

        watcher = asyncio.create_task(monitor())
        try:
            old = {p["metadata"]["uid"] for p in await pods()}
            await kubectl("set", "env", "deployment/event-gateway", "LAB_ROLLOUT_REVISION=2")
            await kubectl("rollout", "status", "deployment/event-gateway", "--timeout=120s")
            new = {p["metadata"]["uid"] for p in await pods() if not p["metadata"].get("deletionTimestamp")}
            assert old.isdisjoint(new)
            results["rolling_template_update"] = {
                "old_pods_replaced": True,
                "note": "Same application binary, changed pod-template environment; not a new business feature release.",
            }
            await kubectl("rollout", "undo", "deployment/event-gateway")
            await kubectl("rollout", "status", "deployment/event-gateway", "--timeout=120s")
            current = [p for p in await pods() if not p["metadata"].get("deletionTimestamp")]
            victim = current[0]
            removed = victim["metadata"]["uid"]
            await kubectl("delete", "pod", victim["metadata"]["name"], "--wait=true")
            for _ in range(100):
                live = [p for p in await pods() if not p["metadata"].get("deletionTimestamp")]
                ready = [
                    p
                    for p in live
                    if any(
                        c["type"] == "Ready" and c["status"] == "True"
                        for c in p.get("status", {}).get("conditions", [])
                    )
                ]
                if len(ready) >= 2 and removed not in {p["metadata"]["uid"] for p in ready}:
                    break
                await asyncio.sleep(0.5)
            else:
                raise TimeoutError("Deleted own pod was not recovered")
            results["pod_recovery"] = {"deleted_uid_replaced": True, "ready_at_least": 2}
            template = json.loads(await kubectl("get", "deployment", "event-gateway", "-o", "json"))["spec"][
                "template"
            ]
            probe = {
                "apiVersion": "v1",
                "kind": "Pod",
                "metadata": {"name": "quota-rejection-probe", "namespace": "internship-lab"},
                "spec": template["spec"],
            }
            probe["spec"]["containers"][0]["resources"]["requests"]["cpu"] = "1000m"
            probe["spec"]["containers"][0]["resources"]["limits"]["cpu"] = "1000m"
            code, text = await command(
                "create", "--dry-run=server", "-f", "-", stdin=json.dumps(probe), expect=None
            )
            assert code != 0 and "exceeded quota" in text, text
            results["quota"] = {
                "oversized_server_dry_run_rejected": True,
                "no_real_oversized_pod_created": True,
            }
        finally:
            stop.set()
            await watcher
        # Real resource metrics from own kubelets, not fabricated external metrics.
        for _ in range(70):
            code, top = await command("-n", "internship-lab", "top", "pods", expect=None)
            if code == 0 and "event-gateway" in top:
                break
            await asyncio.sleep(1)
        else:
            raise TimeoutError("Resource metrics unavailable")
        load_end = time.monotonic() + 75

        async def load():
            while time.monotonic() < load_end:
                try:
                    await client.get("http://127.0.0.1:18000/actuator/health")
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(0.005)

        tasks = [asyncio.create_task(load()) for _ in range(16)]
        history = []
        try:
            for _ in range(28):
                hpa = json.loads(await kubectl("get", "hpa", "event-gateway", "-o", "json"))
                status = hpa.get("status", {})
                history.append(
                    {
                        "desired": status.get("desiredReplicas"),
                        "current": status.get("currentReplicas"),
                        "metrics": status.get("currentMetrics", []),
                    }
                )
                if status.get("desiredReplicas", 0) >= 3:
                    break
                await asyncio.sleep(3)
            else:
                raise AssertionError("CPU HPA did not request an additional pod")
        finally:
            load_end = time.monotonic()
            await asyncio.gather(*tasks)
        await kubectl("rollout", "status", "deployment/event-gateway", "--timeout=120s")
        results["hpa"] = {
            "min": 2,
            "max": 3,
            "observed_desired": max(h.get("desired") or 0 for h in history),
            "history": history,
            "source": "real metrics-server v0.9.1 CPU samples; synthetic health-request load",
        }
        results |= {
            "status": "PASS",
            "namespace": "internship-lab",
            "cluster": "kind-internship-lab",
            "version": json.loads((await command("version", "-o", "json"))[1])["serverVersion"]["gitVersion"],
            "health_samples": len(samples),
            "health_non200": sum(s != 200 for s in samples),
            "backend_unavailable_expected": 503,
            "scope": "Single-node local Gateway-component experiment; Compose business/DB/Agent stopped for resource budget. Health continuity is not end-to-end booking availability or production HA. Only lab kubelet metrics uses the documented self-signed-certificate test flag.",
        }
        out = ROOT / "repo-agent/.local/k8s_probe.json"
        out.write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(json.dumps(results))


if __name__ == "__main__":
    asyncio.run(main())
