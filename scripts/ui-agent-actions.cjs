async (page) => {
  const downloads=page.waitForEvent('download');
  await page.getByRole('button',{name:'导出证据报告',exact:true}).click();
  const download=await downloads;
  if(download.suggestedFilename()!=='research-report.json')throw Error('Wrong report filename');
  await page.getByRole('textbox',{name:'继续追问同一仓库版本',exact:true}).fill('只补充构建后端');
  await page.getByRole('button',{name:'发送追问',exact:true}).click();
  await page.getByRole('heading',{name:'只补充构建后端',exact:true}).waitFor();
  await page.getByRole('link',{name:'E1 · pyproject.toml:1–10',exact:true}).waitFor();
  await page.screenshot({path:'output/playwright/research-v2.png',fullPage:true});
  console.log('UI contract fixture: report aliases/export download/followup PASS; backend real integration is separately recorded');
}
