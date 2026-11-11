package com.enterpriseos;
import java.util.*;
import javax.naming.directory.InitialDirContext;
import org.springframework.stereotype.Service;
@Service public class DnsTxtResolver {
    public List<String> lookup(String name){Hashtable<String,String> environment=new Hashtable<>();environment.put("java.naming.factory.initial","com.sun.jndi.dns.DnsContextFactory");environment.put("com.sun.jndi.dns.timeout.initial","2000");environment.put("com.sun.jndi.dns.timeout.retries","1");InitialDirContext context=null;
        try{context=new InitialDirContext(environment);var values=context.getAttributes(name,new String[]{"TXT"}).get("TXT");List<String> records=new ArrayList<>();if(values!=null)for(int i=0;i<values.size();i++)records.add(values.get(i).toString().replace("\"","").replace(" ",""));return records;}catch(javax.naming.NamingException ex){throw new ApiException(409,"DNS TXT record is not available yet. Publish the challenge and retry after propagation.");}finally{if(context!=null)try{context.close();}catch(Exception ignored){}}
    }
}
