package com.enterpriseos;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {
    private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final JdbcTemplate db; private final byte[] encryptionKey;
    public MfaService(JdbcTemplate db,@Value("${APP_ENCRYPTION_KEY:}")String key){this.db=db;encryptionKey=key.matches("[0-9a-fA-F]{64}")?HexFormat.of().parseHex(key):null;}
    public Map<String,Object> status(String user){return Map.of("configured",encryptionKey!=null,"enabled",db.queryForObject("SELECT COUNT(*) FROM user_mfa WHERE user_id=? AND enabled=TRUE",Integer.class,user)>0);}
    @Transactional public String prepare(String user){
        configured();db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",user);
        if((boolean)status(user).get("enabled"))throw new ApiException(409,"MFA is already enabled. Disable it before replacing the authenticator.");
        byte[] secret=new byte[20];new SecureRandom().nextBytes(secret);String encoded=base32(secret);
        db.update("INSERT INTO user_mfa(user_id,pending_secret) VALUES (?,?) ON DUPLICATE KEY UPDATE pending_secret=VALUES(pending_secret)",user,encrypt(encoded));return encoded;
    }
    @Transactional public List<String> enable(String user,String otp){
        configured();var rows=db.queryForList("SELECT * FROM user_mfa WHERE user_id=? FOR UPDATE",user);
        if(rows.isEmpty()||truth(rows.getFirst().get("enabled"))||rows.getFirst().get("pending_secret").toString().isBlank())throw new ApiException(409,"Start authenticator setup first.");
        String secret=decrypt(rows.getFirst().get("pending_secret").toString());long counter=match(secret,otp,-1);
        db.update("UPDATE user_mfa SET encrypted_secret=pending_secret,pending_secret='',enabled=TRUE,last_counter=? WHERE user_id=?",counter,user);
        db.update("DELETE FROM mfa_recovery_codes WHERE user_id=?",user);List<String> codes=new ArrayList<>();
        for(int i=0;i<10;i++){String code=UUID.randomUUID().toString().replace("-","").substring(0,20);codes.add(code);db.update("INSERT INTO mfa_recovery_codes(user_id,code_hash) VALUES (?,?)",user,Tokens.hash(code));}return codes;
    }
    @Transactional public void verifyLogin(String user,String otp){
        var rows=db.queryForList("SELECT * FROM user_mfa WHERE user_id=? FOR UPDATE",user);if(rows.isEmpty()||!truth(rows.getFirst().get("enabled")))return;
        configured();if(otp==null||otp.isBlank())throw new ApiException(401,"Enter your authenticator code or an unused recovery code.");
        if(otp.matches("[a-fA-F0-9]{20}")){
            int changed=db.update("UPDATE mfa_recovery_codes SET used_at=UTC_TIMESTAMP(6) WHERE user_id=? AND code_hash=? AND used_at IS NULL",user,Tokens.hash(otp.toLowerCase(Locale.ROOT)));
            if(changed!=1)throw new ApiException(401,"Authenticator or recovery code is invalid or already used.");return;
        }
        var row=rows.getFirst();long counter=match(decrypt(row.get("encrypted_secret").toString()),otp,((Number)row.get("last_counter")).longValue());
        db.update("UPDATE user_mfa SET last_counter=? WHERE user_id=?",counter,user);
    }
    @Transactional public void disable(String user,String otp){verifyLogin(user,otp);db.update("DELETE FROM mfa_recovery_codes WHERE user_id=?",user);db.update("DELETE FROM user_mfa WHERE user_id=?",user);}
    private long match(String secret,String otp,long last){if(otp==null||!otp.matches("[0-9]{6}"))throw new ApiException(401,"Enter a six-digit authenticator code.");long now=Instant.now().getEpochSecond()/30;for(long counter=now-1;counter<=now+1;counter++)if(counter>last&&java.security.MessageDigest.isEqual(totp(secret,counter).getBytes(StandardCharsets.US_ASCII),otp.getBytes(StandardCharsets.US_ASCII)))return counter;throw new ApiException(401,"Authenticator code is invalid, expired or already used. Wait for the next code.");}
    static String totp(String secret,long counter){try{Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(unbase32(secret),"HmacSHA1"));byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());int offset=hash[hash.length-1]&15;int binary=((hash[offset]&127)<<24)|((hash[offset+1]&255)<<16)|((hash[offset+2]&255)<<8)|(hash[offset+3]&255);return String.format(Locale.ROOT,"%06d",binary%1_000_000);}catch(Exception e){throw new IllegalStateException(e);}}
    private void configured(){if(encryptionKey==null)throw new ApiException(503,"Configure a persistent 64-hex APP_ENCRYPTION_KEY before enabling MFA.");}
    private String encrypt(String text){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12+encrypted.length).put(iv).put(encrypted).array());}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String decrypt(String text){try{byte[] bytes=Base64.getDecoder().decode(text);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));return new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8);}catch(Exception ex){throw new ApiException(503,"Authenticator encryption key is unavailable or changed. Restore the installation key.");}}
    static String base32(byte[] data){StringBuilder out=new StringBuilder();int buffer=0,bits=0;for(byte b:data){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){out.append(ALPHABET.charAt((buffer>>(bits-5))&31));bits-=5;}}if(bits>0)out.append(ALPHABET.charAt((buffer<<(5-bits))&31));return out.toString();}
    static byte[] unbase32(String value){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;for(char c:value.toCharArray()){int index=ALPHABET.indexOf(c);if(index<0)throw new IllegalArgumentException();buffer=(buffer<<5)|index;bits+=5;if(bits>=8){out.write((buffer>>(bits-8))&255);bits-=8;}}return out.toByteArray();}
    private static boolean truth(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()!=0);}
}
