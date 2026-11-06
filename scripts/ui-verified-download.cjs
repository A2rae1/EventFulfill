async page=>{
 const done=page.waitForEvent('download');
 await page.getByRole('button',{name:'下载已校验资料包',exact:true}).click();
 const download=await done;await download.saveAs('output/playwright/ui-verified-download.zip');
 return {filename:download.suggestedFilename(),failure:await download.failure()};
}
