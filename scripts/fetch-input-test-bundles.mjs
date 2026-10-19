// Fetch only the public, pinned bundles used by the compatibility regression.
// No login/cookies. Source archives stay outside git with private permissions.
import {mkdir,readFile,writeFile,chmod} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
const output=process.argv[2];
if(!output)throw Error('Usage: node scripts/fetch-input-test-bundles.mjs PRIVATE_OUTPUT_DIRECTORY');
const hashes={
  'bundle_core.js':'10f2992e52b4347d5ca699d40ae272a4b7d0a6d5703c6f59666ec8a32abc8ce4',
  'bundle_vendor.js':'72336f21d30d70e5c9ce2ced6d596f9d060739b2405de688d5e1c74c766cc61d'
};
await mkdir(output,{recursive:true,mode:0o700});
await chmod(output,0o700);
for(const [name,expected] of Object.entries(hashes)){
  const dest=path.join(output,name);
  let bytes;
  try{bytes=await readFile(dest);}catch(error){if(error.code!=='ENOENT')throw error;}
  if(!bytes){
    const response=await fetch(`https://beastacademy.com/dist/v12.119.0/${name}`,{
      headers:{'User-Agent':'Mozilla/5.0'},signal:AbortSignal.timeout(60000)});
    if(!response.ok)throw Error(`${name}: HTTP ${response.status}`);
    bytes=Buffer.from(await response.arrayBuffer());
  }
  if(createHash('sha256').update(bytes).digest('hex')!==expected)
    throw Error(`${name}: hash mismatch; refusing an unqualified replacement`);
  await writeFile(dest,bytes,{mode:0o600});
  await chmod(dest,0o600);
  console.log(`Verified ${name}`);
}
