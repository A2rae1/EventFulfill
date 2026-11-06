"""Initialize only this project's registry; never print admin/token responses."""

import asyncio
import os
import time

import httpx


async def main():
    origin = "http://127.0.0.1:18848"
    async with httpx.AsyncClient(timeout=10) as client:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                # An already initialized deployment is never reset.
                await client.post(
                    origin + "/nacos/v3/auth/user/admin", data={"password": os.environ["NACOS_PASSWORD"]}
                )
                login = await client.post(
                    origin + "/nacos/v3/auth/user/login",
                    data={"username": "nacos", "password": os.environ["NACOS_PASSWORD"]},
                )
                if login.status_code == 200:
                    payload = login.json()
                    payload = payload.get("data", payload)
                    if isinstance(payload, dict) and payload.get("accessToken"):
                        break
            except httpx.HTTPError:
                pass
            await asyncio.sleep(1)
        else:
            raise RuntimeError("Own registry was not ready/authenticated; existing data was not reset")
        headers = {"accessToken": payload["accessToken"]}
        existing = await client.get(
            origin + "/nacos/v3/client/cs/config",
            headers=headers,
            params={"dataId": "gateway-policy.properties", "groupName": "PROJECT"},
        )
        if existing.status_code != 200 or existing.json().get("code") not in {0, 200}:
            published = await client.post(
                origin + "/nacos/v3/admin/cs/config",
                headers=headers,
                data={
                    "dataId": "gateway-policy.properties",
                    "groupName": "PROJECT",
                    "content": "agent_admission_enabled=true\n",
                    "type": "properties",
                },
            )
            if published.status_code != 200 or published.json().get("code") not in {0, 200}:
                raise RuntimeError("Registry policy initialization failed")
        print(
            "Own authenticated registry initialized; existing configuration and credentials not printed or reset."
        )


if __name__ == "__main__":
    asyncio.run(main())
