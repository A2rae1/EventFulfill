async page=>{
 await page.evaluate(()=>{
  const original=URL.createObjectURL.bind(URL);
  URL.createObjectURL=blob=>{window.__labBlobSize=blob.size;return original(blob)};
 });
 const responsePromise=page.waitForResponse(r=>r.url().endsWith('/uploads/66bc57dbc9a544d39ae37c9268ff89b7/download'));
 const downloadPromise=page.waitForEvent('download');
 await page.getByRole('button',{name:'下载已校验资料包',exact:true}).click();
 const response=await responsePromise;
 const download=await downloadPromise;
 const facts={responseStatus:response.status(),fromServiceWorker:response.fromServiceWorker(),serverAddr:await response.serverAddr(),method:response.request().method(),url:response.url(),headers:response.headers(),browserResponseBytes:(await response.body()).length,blobSize:await page.evaluate(()=>window.__labBlobSize),downloadFailure:await download.failure()};
 await download.saveAs('output/playwright/download-debug.zip');return facts;
}
