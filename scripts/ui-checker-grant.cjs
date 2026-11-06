async (page) => {
  await page.getByRole('combobox',{name:'场次',exact:true}).nth(1).selectOption({label:'公开技术分享·验证场次'});
  await page.getByRole('textbox',{name:'核验员账号',exact:true}).fill('checker');
  await page.getByRole('button',{name:'授予核验权限',exact:true}).click();
  await page.getByRole('status').filter({hasText:'已授予该场次核验权限'}).waitFor();
  console.log('UI explicit checker grant PASS, temporary H2 lab only');
}
