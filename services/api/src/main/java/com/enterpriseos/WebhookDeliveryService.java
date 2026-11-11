package com.enterpriseos;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookDeliveryService {
    private final AutomationSupport s;private final Set<String> hosts;private final SecretCipher secrets;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public WebhookDeliveryService(AutomationSupport s,SecretCipher secrets,@Value("${APP_WEBHOOK_ALLOWED_HOSTS:}")String hosts){this.s=s;this.secrets=secrets;this.hosts=new HashSet<>();for(String host:hosts.split(","))if(!host.isBlank())this.hosts.add(host.trim().toLowerCase(Locale.ROOT));}
    URI destination(String url){try{URI uri=URI.create(url);String host=uri.getHost();if(!"https".equals(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IllegalArgumentException();if(!hosts.contains(host.toLowerCase(Locale.ROOT)))throw new ApiException(400,"Webhook host must be explicitly allowed in APP_WEBHOOK_ALLOWED_HOSTS by the operator.");for(InetAddress ip:InetAddress.getAllByName(host))if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()||(ip instanceof Inet6Address&&((ip.getAddress()[0]&0xfe)==0xfc)))throw new ApiException(400,"Webhook destinations must resolve to public addresses.");return uri;}catch(ApiException ex){throw ex;}catch(Exception ex){throw new ApiException(400,"Webhook URL must be a resolvable public HTTPS address on port 443.");}}
    @Scheduled(fixedDelayString="${APP_WEBHOOK_POLL_MS:30000}") @Transactional
    public void dispatch(){var jobs=s.db.queryForList("SELECT d.*,e.url,e.secret FROM webhook_deliveries d JOIN webhook_endpoints e ON e.id=d.endpoint_id JOIN tenants t ON t.id=d.tenant_id WHERE d.status='PENDING' AND d.next_attempt_at<=UTC_TIMESTAMP(6) AND e.status='ACTIVE' AND e.direction='OUTBOUND' AND t.status='ACTIVE' ORDER BY d.created_at LIMIT 5 FOR UPDATE SKIP LOCKED");for(var job:jobs){int attempt=((Number)job.get("attempts")).intValue()+1;String id=job.get("id").toString();try{var sub=s.entitlements.subscription(job.get("tenant_id").toString());if(!sub.isEmpty())s.entitlements.writable(sub);URI uri=destination(job.get("url").toString());String timestamp=Long.toString(Instant.now().getEpochSecond()),payload=job.get("payload").toString();var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-EOS-Event-Id",job.get("event_id").toString()).header("X-EOS-Timestamp",timestamp).header("X-EOS-Signature","sha256="+signature(secrets.decrypt(job.get("secret").toString()),timestamp,payload.getBytes(StandardCharsets.UTF_8))).POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8)).build();int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();if(status>=200&&status<300)s.db.update("UPDATE webhook_deliveries SET status='DELIVERED',attempts=?,response_code=?,delivered_at=UTC_TIMESTAMP(6) WHERE id=?",attempt,status,id);else retry(id,attempt,status);}catch(Exception ex){retry(id,attempt,null);}}}
    private void retry(String id,int attempt,Integer response){s.db.update("UPDATE webhook_deliveries SET status=?,attempts=?,response_code=?,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) WHERE id=?",attempt>=5?"FAILED":"PENDING",attempt,response,Math.min(3600,30*(1<<Math.min(attempt,6))),id);}
    public static String signature(String secret,String timestamp,byte[] payload){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(mac.doFinal(payload));}catch(Exception ex){throw new IllegalStateException(ex);}}
    public static boolean valid(String secret,String timestamp,byte[] payload,String signature){if(signature==null||!signature.matches("sha256=[0-9a-fA-F]{64}"))return false;return java.security.MessageDigest.isEqual(signature(secret,timestamp,payload).getBytes(StandardCharsets.US_ASCII),signature.substring(7).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));}
}
