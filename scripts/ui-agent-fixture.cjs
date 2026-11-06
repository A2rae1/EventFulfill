async (page) => {
  // Browser interaction contract fixture. Real services were tested separately;
  // this checks rendering/actions without injecting project credentials into a browser.
  const id='11111111-1111-4111-8111-111111111111', child='22222222-2222-4222-8222-222222222222';
  let jobs=[{id,status:'COMPLETED',question:'Python最低版本是什么',commitSha:'a'.repeat(40)}];
  await page.route('**/api/agent/workspaces',route=>route.fulfill({json:[{id:'fixture-workspace',name:'界面合同验证'}]}));
  await page.route('**/api/agent/jobs',route=>route.fulfill({json:jobs}));
  await page.route('**/api/agent/jobs/*/followup',async route=>{const body=route.request().postDataJSON();const job={id:child,status:'COMPLETED',question:body.question,commitSha:'a'.repeat(40)};jobs=[job,...jobs];await route.fulfill({json:job})});
  await page.route('**/agent/jobs/*/events',route=>route.fulfill({json:[{seq:1,kind:'snapshot_ready',data:{commit:'a'.repeat(40)}},{seq:2,kind:'job_status',data:{status:'COMPLETED'}}]}));
  await page.route('**/agent/jobs/*/stream?*',route=>route.fulfill({contentType:'text/event-stream',body:': heartbeat\n\n'}));
  await page.route('**/agent/jobs/*/report',route=>route.fulfill({json:{answer:'声明Python>=3.7 [E1]。未执行测试。',citations:[{id:'b'.repeat(64),labels:['E1'],path:'pyproject.toml',start_line:1,end_line:10,url:'https://github.com/pallets/click'}],warning:null}}));
  await page.route('**/agent/jobs/*/export',route=>route.fulfill({json:{id:'c'.repeat(64)}}));
  await page.route('**/agent/jobs/*/artifacts/*',route=>route.fulfill({contentType:'application/json',headers:{'Content-Disposition':'attachment; filename="research-report.json"'},body:'{"fixture":true}'}));
  await page.getByRole('button',{name:'仓库调研',exact:true}).click();
}
