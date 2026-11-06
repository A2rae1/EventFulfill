async (page) => {
  await page.getByRole('button',{name:'查看入场凭证',exact:true}).click();
  const token=await page.locator('.ticket code').innerText();
  await page.getByRole('button',{name:'退出',exact:true}).click();
  await page.getByRole('textbox',{name:'账号',exact:true}).fill('organizer');
  await page.getByRole('textbox',{name:'密码',exact:true}).fill('ui-lab-only-2026');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByRole('button',{name:'活动管理',exact:true}).click();
  await page.getByRole('textbox',{name:'凭证编号',exact:true}).fill(token);
  await page.getByRole('button',{name:'核验入场',exact:true}).click();
  await page.getByRole('status').filter({hasText:'核验成功，可以入场'}).waitFor();
  await page.getByRole('textbox',{name:'凭证编号',exact:true}).fill(token);
  await page.getByRole('button',{name:'核验入场',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'票据无效或已核验'}).waitFor();
  await page.getByRole('textbox',{name:'凭证编号',exact:true}).fill('');
  await page.screenshot({path:'output/playwright/checkin.png',fullPage:true});
  console.log('UI publish -> accept -> allocate -> confirm -> ticket -> checkin -> duplicate rejection: PASS (H2 lab)');
}
