package com.enterpriseos;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentBlobStoreTest {
    @TempDir Path local;
    @Test void signerMatchesPublishedAwsS3SignatureVector(){String empty="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";String header=DocumentBlobStore.authorization("GET","/test.txt",Map.of("host","examplebucket.s3.amazonaws.com","range","bytes=0-9","x-amz-date","20130524T000000Z","x-amz-content-sha256",empty),new byte[0],"us-east-1","EXAMPLEACCESSKEY","wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");assertTrue(header.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));}
    @Test void localAdapterPreservesBytesAndRejectsOtherTenantOrTraversal(){var store=new DocumentBlobStore(local.toString(),"","","us-east-1","","","",false);String tenant=UUID.randomUUID().toString(),key=tenant+"-"+UUID.randomUUID()+".bin";byte[] data="Actual private bytes".getBytes(StandardCharsets.UTF_8);assertEquals("LOCAL",store.put(tenant,key,data));assertArrayEquals(data,store.read(tenant,key,"LOCAL"));assertEquals(404,assertThrows(ApiException.class,()->store.read(UUID.randomUUID().toString(),key,"LOCAL")).status());assertEquals(404,assertThrows(ApiException.class,()->store.read(tenant,"../secrets","LOCAL")).status());store.delete(tenant,key,"LOCAL");assertEquals(404,assertThrows(ApiException.class,()->store.read(tenant,key,"LOCAL")).status());}
}
