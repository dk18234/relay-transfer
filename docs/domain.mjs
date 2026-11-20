export const MiB=1024*1024;
export const terminal=new Set(['COMPLETED','FAILED_INTEGRITY','CANCELLED','EXPIRED']);
export function acceptedParts(t){return t.parts?.filter(p=>p.acceptedAt).length??t.acceptedParts??0;}
export function acceptedBytes(t){return t.parts?.filter(p=>p.acceptedAt).reduce((n,p)=>n+p.size,0)??t.acceptedBytes??0;}
export function progress(t){return Math.min(100,Math.floor(acceptedBytes(t)/t.size*100));}
export function missingParts(t){return t.parts.filter(p=>!p.acceptedAt).map(p=>p.number);}
export function formatBytes(n){if(n>=1024**3)return(n/1024**3).toFixed(2)+' GiB';if(n>=MiB)return(n/MiB).toFixed(n%MiB?1:0)+' MiB';if(n>=1024)return(n/1024).toFixed(1)+' KiB';return n+' B';}
export function label(t,running=false){if(running)return'Transferring';return({OPEN:acceptedParts(t)?'Ready to resume':'Ready',VERIFYING:'Verifying',COMPLETED:'Verified',FAILED_INTEGRITY:'Integrity failed',VERIFY_FAILED:'Verification paused',CANCELLED:'Cancelled',EXPIRED:'Expired'})[t.status]??t.status;}
export function escapeHtml(value){return String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
export function validateFile(t,file,sha){if(t.size!==file.size||t.expectedSha256!==sha)throw new Error('This file does not match the original manifest. Select the original file to resume.');}
export function sampleFile(name='nmr-spectrum-run17.bin',size=24*MiB){const blocks=[];for(let offset=0;offset<size;offset+=MiB){const b=new Uint8Array(Math.min(MiB,size-offset));for(let j=0;j<b.length;j++)b[j]=((offset+j)*31+((offset+j)>>>8)*17+42)&255;blocks.push(b);}const blob=new Blob(blocks,{type:'application/octet-stream'});return new File([blob],name,{type:'application/octet-stream',lastModified:0});}
