const fs=require('node:fs'),crypto=require('node:crypto'),zlib=require('node:zlib');
try {
 const [action,input,output]=process.argv.slice(2),phrase=process.env.EOS_BACKUP_PASSPHRASE;
 if(!phrase||phrase.length<16)throw Error('Backup passphrase must contain at least 16 characters.');
 if(!input||!output)throw Error('Provide input and output paths.');
 if(action==='pack'){
  const bytes=fs.readFileSync(input),salt=crypto.randomBytes(16),iv=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',crypto.scryptSync(phrase,salt,32),iv);
  const content=zlib.gzipSync(bytes),encrypted=Buffer.concat([cipher.update(content),cipher.final()]);
  fs.writeFileSync(output,Buffer.concat([Buffer.from('EOSBKP01'),salt,iv,cipher.getAuthTag(),encrypted]),{mode:0o600});
 }else if(action==='unpack'){
  const file=fs.readFileSync(input);if(file.subarray(0,8).toString()!=='EOSBKP01')throw Error('Unknown backup format.');
  const decipher=crypto.createDecipheriv('aes-256-gcm',crypto.scryptSync(phrase,file.subarray(8,24),32),file.subarray(24,36));decipher.setAuthTag(file.subarray(36,52));
  const bytes=zlib.gunzipSync(Buffer.concat([decipher.update(file.subarray(52)),decipher.final()]),{maxOutputLength:768*1024*1024});
  JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));fs.writeFileSync(output,bytes,{mode:0o600});
 }else throw Error('Use pack or unpack.');
}catch(e){console.error(e.message);process.exitCode=1;}
