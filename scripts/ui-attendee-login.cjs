async (page) => {
  await page.getByRole('textbox', {name:'账号'}).fill('alice');
  await page.getByRole('textbox', {name:'密码'}).fill('ui-lab-only-2026');
  await page.getByRole('button', {name:'登录',exact:true}).click();
  await page.getByRole('button', {name:'我的预约',exact:true}).waitFor();
}
