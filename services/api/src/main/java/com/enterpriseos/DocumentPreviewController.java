package com.enterpriseos;

import java.util.Set;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class DocumentPreviewController {
    private final DocumentController documents;private final CustomAccessPolicy custom;
    public DocumentPreviewController(DocumentController documents,CustomAccessPolicy custom){this.documents=documents;this.custom=custom;}
    @GetMapping("/api/v1/org/documents/{id}/preview") public ResponseEntity<byte[]> preview(@RequestAttribute Principal principal,@PathVariable String id,@RequestParam(required=false)Integer version){if(!custom.allowed(principal,"GET","/api/v1/org/documents/"+id+"/download"))throw new ApiException(403,"Custom role cannot read this document content.");ResponseEntity<byte[]> file=documents.download(principal,id,version,null);MediaType type=file.getHeaders().getContentType();String mime=type==null?"":type.getType()+"/"+type.getSubtype();if(!Set.of("application/pdf","image/png","image/jpeg","image/webp","text/plain","text/csv").contains(mime))throw new ApiException(415,"This document cannot be previewed safely.");if(mime.startsWith("text/"))type=new MediaType("text","plain",java.nio.charset.StandardCharsets.UTF_8);return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).header("Content-Disposition","inline").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'; frame-ancestors 'self'").header("Referrer-Policy","no-referrer").body(file.getBody());}
}
