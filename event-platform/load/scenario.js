import http from 'k6/http';
import {check} from 'k6';
import exec from 'k6/execution';
const data=JSON.parse(open(__ENV.LOAD_INPUT));
const mode=__ENV.LOAD_MODE || 'warm';
export const options={scenarios:{bounded:{executor:'shared-iterations',vus:20,iterations:mode==='booking'?100:200,maxDuration:'60s'}},thresholds:{checks:['rate==1'],http_req_failed:['rate==0']},summaryTrendStats:['min','med','p(90)','p(95)','max'],noVUConnectionReuse:false};
export default function(){
 const index=exec.scenario.iterationInTest;
 const auth={'Authorization':'Bearer '+data.tokens[index%100]};
 if(mode==='booking'){
  const key=data.run+'-'+index; const params={headers:{...auth,'Content-Type':'application/json','Idempotency-Key':key},tags:{operation:'booking_accept'}};
  const first=http.post(data.origin+'/api/booking-requests',JSON.stringify({sessionId:data.bookingSession}),params);
  const replay=http.post(data.origin+'/api/booking-requests',JSON.stringify({sessionId:data.bookingSession}),params);
  check(first,{'accepted202':r=>r.status===202});
  check(replay,{'replay_same_request':r=>r.status===202&&r.json('id')===first.json('id')});
 }else{
  const id=mode==='cold'?data.coldSessions[index]:data.warmSession;
  const response=http.get(data.origin+'/api/sessions/'+id,{headers:auth,tags:{operation:mode+'_detail'}});
  check(response,{'published detail':r=>r.status===200&&r.json('id')===id});
 }
}
export function handleSummary(summary){return {[__ENV.LOAD_OUTPUT]:JSON.stringify(summary,null,2)};}
