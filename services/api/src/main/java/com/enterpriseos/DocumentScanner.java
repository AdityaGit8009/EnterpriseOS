package com.enterpriseos;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentScanner {
    private final String host;private final int port;
    public DocumentScanner(@Value("${APP_CLAMAV_HOST:}")String host,@Value("${APP_CLAMAV_PORT:3310}")int port){this.host=host;this.port=port;}
    record Result(String status,String evidence){}
    Result scan(byte[] content){if(host.isBlank())return new Result("PENDING_REVIEW","No malware scanner configured; independent manual review is required.");try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(host,port),3000);socket.setSoTimeout(10000);var output=new DataOutputStream(socket.getOutputStream());output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));for(int at=0;at<content.length;at+=8192){int length=Math.min(8192,content.length-at);output.writeInt(length);output.write(content,at,length);}output.writeInt(0);output.flush();var buffer=new java.io.ByteArrayOutputStream();int b;while(buffer.size()<1000&&(b=socket.getInputStream().read())>0)buffer.write(b);String result=buffer.toString(StandardCharsets.UTF_8);if(result.contains("FOUND"))return new Result("REJECTED",result);if(result.endsWith("OK"))return new Result("CLEAN","ClamAV: "+result);return new Result("SCAN_FAILED","Scanner did not return a clean verdict; independent review required.");}catch(Exception ex){return new Result("SCAN_FAILED","Scanner unavailable; independent review required.");}}
}
