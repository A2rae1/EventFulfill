async (page) => {
  // Public fixture password, valid only in the isolated in-memory UI lab.
  await page.getByRole('textbox', {name:'账号'}).fill('organizer');
  await page.getByRole('textbox', {name:'密码'}).fill('ui-lab-only-2026');
  await page.getByRole('button', {name:'登录',exact:true}).click();
  await page.getByRole('button', {name:'活动管理',exact:true}).waitFor();
  console.log('Organizer login succeeded');
}
