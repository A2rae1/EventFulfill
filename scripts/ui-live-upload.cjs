async (page) => {
 const fixture=process.env.UPLOAD_FIXTURE || '.local/ui-resume.zip';
 let failedOnce=false,part1=0,part2=0;
 const pattern='**/agent/uploads/*/parts/*';
 await page.route(pattern,async route=>{
  const part=Number(route.request().url().split('/').at(-1));
  if(part===1)part1++;if(part===2)part2++;
  if(part===2&&!failedOnce){failedOnce=true;await route.abort('failed');return;}
  await route.continue();
 });
 try{
  let chooserPromise=page.waitForEvent('filechooser');await page.getByRole('button',{name:'Choose File',exact:true}).click();await(await chooserPromise).setFiles(fixture);
  await page.getByRole('alert').waitFor();
  if(!failedOnce)throw Error('Expected injected network interruption');
  chooserPromise=page.waitForEvent('filechooser');await page.getByRole('button',{name:'Choose File',exact:true}).click();await(await chooserPromise).setFiles(fixture);
  await page.getByRole('status').filter({hasText:'已完成上传和完整性校验'}).waitFor();
  if(part1!==1||part2!==2)throw Error('Resume failed: part1='+part1+' part2='+part2);
  const done=page.waitForEvent('download');await page.getByRole('button',{name:'下载已校验资料包',exact:true}).click();const download=await done;
  await download.saveAs('output/playwright/ui-resume-download.zip');
  if(await download.failure())throw Error('Download failed');
  await page.screenshot({path:'output/playwright/ui-live-upload.png',fullPage:true});
  console.log('Real upload network-interruption/resume: part1='+part1+' part2='+part2+'; same file selection worked, artifact saved for SHA256 check.');
 }finally{await page.unroute(pattern);}
}
