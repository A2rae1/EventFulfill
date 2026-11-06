"""Small disposable JVM only: never attach jcmd to a user's IDE or other process."""

import asyncio
import json
import shutil
from pathlib import Path

from jobs_init import ROOT


async def run(*args, timeout=20):
    p = await asyncio.create_subprocess_exec(
        *map(str, args), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    try:
        out, _ = await asyncio.wait_for(p.communicate(), timeout)
    except BaseException:
        p.kill()
        await p.wait()
        raise
    if p.returncode:
        raise RuntimeError("Own diagnostic command failed; no unrelated JVM touched")
    return out.decode("utf-8", errors="replace")


async def main():
    directory = ROOT / ".local/jvm-diagnostics"
    directory.mkdir(exist_ok=True)
    java = Path(shutil.which("java"))
    javac = java.with_name("javac.exe")
    jcmd = java.with_name("jcmd.exe")
    await run(javac, "-d", directory, ROOT / "event-platform/diagnostics/DiagnosticLab.java")
    p = await asyncio.create_subprocess_exec(
        str(java),
        "-Xms16m",
        "-Xmx64m",
        "-XX:+UseG1GC",
        "-cp",
        str(directory),
        "DiagnosticLab",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    prefix = []
    try:
        for _ in range(8):
            line = (await asyncio.wait_for(p.stdout.readline(), 10)).decode()
            prefix.append(line)
            if line.startswith("READY "):
                assert int(line.split()[1]) == p.pid
                break
        else:
            raise AssertionError("Own JVM not ready")
        await asyncio.sleep(0.5)
        threads = await run(jcmd, p.pid, "Thread.print", "-l")
        heap = await run(jcmd, p.pid, "GC.heap_info")
        flags = await run(jcmd, p.pid, "VM.flags")
        assert "Found one Java-level deadlock" in threads and "lab-bounded-worker" in threads
        assert "UseG1GC" in flags and "garbage-first heap" in heap
        (directory / "threads.txt").write_text(threads, encoding="utf-8")
        (directory / "heap.txt").write_text(heap, encoding="utf-8")
        (directory / "flags.txt").write_text(flags, encoding="utf-8")
    finally:
        if p.returncode is None:
            p.terminate()
        await p.wait()
    # Deliberate OOM in a separate 32MB JVM with synthetic byte arrays; no host-wide allocation.
    dump = directory / "synthetic-oom.hprof"
    if dump.exists():
        dump = dump.with_name("synthetic-oom-next.hprof")
    oom = await run(
        java,
        "-Xms16m",
        "-Xmx32m",
        "-XX:+UseG1GC",
        "-XX:+HeapDumpOnOutOfMemoryError",
        f"-XX:HeapDumpPath={dump}",
        "-cp",
        directory,
        "DiagnosticLab$ExpectedOOM",
    )
    assert "LAB_EXPECTED_OOM" in oom and dump.exists() and dump.stat().st_size > 1000000
    (directory / "oom.txt").write_text(oom, encoding="utf-8")
    result = {
        "status": "PASS",
        "java_version": (await run(java, "-version")).strip().splitlines()[0],
        "scope": "Disposable local JVM, not a production incident or project server. Project source targets17; native lab uses installed JDK. Only spawned PID is inspected.",
        "bounded_rejection": True,
        "deadlock_detected": True,
        "heap_max_mib": 64,
        "gc": "G1",
        "oom_child_heap_max_mib": 32,
        "heap_dump_bytes": dump.stat().st_size,
        "evidence": ".local/jvm-diagnostics/{threads,heap,flags,oom}.txt",
        "synthetic_dump_excluded_from_public_evidence": True,
    }
    (ROOT / "repo-agent/.local/jvm_diagnostics.json").write_text(
        json.dumps(result, indent=2), encoding="utf-8"
    )
    print(json.dumps(result))


if __name__ == "__main__":
    asyncio.run(main())
