async (page) => {
  await page.getByRole('textbox',{name:'活动名称',exact:true}).fill('本机端到端验证活动');
  await page.getByRole('textbox',{name:'活动说明',exact:true}).fill('独立H2界面验证，不计真实MySQL集成');
  await page.getByRole('textbox',{name:'场次名称',exact:true}).fill('公开技术分享·验证场次');
  await page.getByRole('spinbutton',{name:'名额',exact:true}).fill('1');
  await page.getByRole('spinbutton',{name:'确认期限（秒）',exact:true}).fill('600');
  await page.getByRole('button',{name:'创建并发布',exact:true}).click();
  await page.getByRole('status').filter({hasText:'活动场次已发布'}).waitFor();
}
