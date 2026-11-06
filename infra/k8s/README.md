# K8s单组件实验与复现

更新：2026-11-06。本机单节点kind仅部署本项目真实Gateway，验证Deployment滚动/回退、Pod故障恢复、Quota和CPU HPA。为2.5GB WSL预算先停Compose，不部署MySQL/票务/Agent；业务API预期503，health200不代表业务链路可用。

## 工具与镜像

工具只在工作区`.local/tools/k8s`：kind0.33.0、kubectl1.35.8，安装时已用官方SHA256校验。新机器从kind.sigs.k8s.io与dl.k8s.io官方发行获取同版本windows-amd64并验证对应.sha256，再放入此目录；不要用别的项目kubeconfig或任意现有集群。Docker Desktop先正常启动。

node1.35.8固定摘要见kind.yml；metrics-server0.9.1原清单在metrics-server.upstream.yml，实验副本只在.local/k8s生成。Gateway本地镜像在全项目verify后由apps-build构建；imagePullPolicy Never不公开推送。

```powershell
./scripts/k8s-start.ps1 -Metrics
# 专项probe有真实CPU负载/滚动操作，仅在这个专用组运行：
uv run --project repo-agent python repo-agent/scripts/k8s_probe.py
./scripts/apps-stop.ps1
```

脚本创建或恢复精确标签/名字且有本项目私有kubeconfig的节点；不删除已有集群，不改全局kubeconfig。重启后先等待API readyz，再等Node Ready；只用私有context kind-internship-lab。镜像通过linux/amd64 image-archive导入，避免多架构manifest缺blob。已有cluster恢复过程中不会清空业务数据。

metrics-server读取本地kind自签kubelet时添加`--kubelet-insecure-tls`，仅限这个不承载真实业务/资料的独立实验；保留上游原清单，不能当生产TLS配置。其集群级RBAC/APIService只在专用kind内写入，不作用于其他集群。Gateway命名空间restricted、非root/只读/降权/cgroup限制，Quota约束资源，HPA2–3副本。

证据k8s_probe.json：真实rollout环境模板更新/undo、删除Pod补建、Quota server-dry-run拒绝、metrics-server采样负载触发HPA3Ready；692健康采样2非200，不宣称零中断或生产HA。模板更新是同binary改env，不冒称业务版本升级。重新执行probe会追加真实实验而不是复用旧结果。

停止保留节点/卷/镜像，恢复脚本将专用node restart设为no，避免下次Docker自动拉起整个实验占内存；应用启动和构建也检查/停本项目节点。禁止删除别的集群、全局prune或触碰旧Windows MySQL。
