package com.example.online_reader.book.reader;

import javax.xml.stream.*;
import java.io.*;

final class ReaderXml {
    @FunctionalInterface interface Event {void accept(XMLStreamReader reader,int depth)throws IOException;}
    static void read(byte[] bytes,ReaderBudget budget,Event event)throws IOException {
        var factory=XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((a,b,c,d)->{throw new XMLStreamException("External XML forbidden");});
        try {
            var r=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));int depth=0;
            try {
                while(r.hasNext()) {
                    budget.check();int token=r.next();
                    if(token==XMLStreamConstants.DTD || token==XMLStreamConstants.ENTITY_REFERENCE)throw new IOException("Invalid XML");
                    if(r.isStartElement() && (++depth>128 || r.getAttributeCount()+r.getNamespaceCount()>64 || "http://www.w3.org/2001/XInclude".equals(r.getNamespaceURI())))throw new IOException("Invalid XML");
                    event.accept(r,depth);if(r.isEndElement())depth--;
                }
            }finally{r.close();}
        }catch(XMLStreamException e){throw new IOException("Invalid XML");}
    }
    static String attr(XMLStreamReader r,String name){return r.getAttributeValue(null,name);}
}
