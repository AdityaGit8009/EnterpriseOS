package com.enterpriseos;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small deterministic paginated text PDFs, with no remote images or executable content. */
final class BusinessPdf {
    private BusinessPdf(){}
    static byte[] document(List<String> lines){
        List<String> wrapped=new ArrayList<>();for(String line:lines){String normalized=line==null?"":line.replace('\r',' ').replace('\n',' ');while(normalized.length()>95){wrapped.add(normalized.substring(0,95));normalized=normalized.substring(95);}wrapped.add(normalized);}
        int pages=Math.max(1,(wrapped.size()+47)/48);List<byte[]> objects=new ArrayList<>();objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));StringBuilder children=new StringBuilder();for(int n=0;n<pages;n++)children.append(4+n*2).append(" 0 R ");objects.add(ascii("<< /Type /Pages /Kids ["+children+"] /Count "+pages+" >>"));objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));
        for(int n=0;n<pages;n++){int contentId=5+n*2;objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents "+contentId+" 0 R >>"));StringBuilder text=new StringBuilder("BT /F1 10 Tf 50 745 Td 14 TL\n");for(int i=n*48;i<Math.min(wrapped.size(),(n+1)*48);i++)text.append('(').append(escape(wrapped.get(i))).append(") Tj T*\n");text.append("ET\n");byte[] stream=ascii(text.toString());ByteArrayOutputStream obj=new ByteArrayOutputStream();obj.writeBytes(ascii("<< /Length "+stream.length+" >>\nstream\n"));obj.writeBytes(stream);obj.writeBytes(ascii("endstream"));objects.add(obj.toByteArray());}
        ByteArrayOutputStream pdf=new ByteArrayOutputStream();pdf.writeBytes(ascii("%PDF-1.4\n"));List<Integer> offsets=new ArrayList<>();offsets.add(0);for(int i=0;i<objects.size();i++){offsets.add(pdf.size());pdf.writeBytes(ascii((i+1)+" 0 obj\n"));pdf.writeBytes(objects.get(i));pdf.writeBytes(ascii("\nendobj\n"));}int xref=pdf.size();pdf.writeBytes(ascii("xref\n0 "+(objects.size()+1)+"\n0000000000 65535 f \n"));for(int i=1;i<offsets.size();i++)pdf.writeBytes(ascii(String.format(Locale.ROOT,"%010d 00000 n \n",offsets.get(i))));pdf.writeBytes(ascii("trailer\n<< /Size "+(objects.size()+1)+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n"));return pdf.toByteArray();
    }
    private static byte[] ascii(String value){return value.getBytes(StandardCharsets.ISO_8859_1);}
    private static String escape(String value){return value.replace("\\","\\\\").replace("(","\\(").replace(")","\\)").replaceAll("[\\p{Cntrl}]"," ");}
}
