package com.example.online_reader.book.reader;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.web.util.HtmlUtils;

/** Version 1 canonical XHTML text. Only application-owned tags/attributes reach HTML. */
final class ChapterText {
    private static final Set<String> BLOCKS=Set.of("p","div","section","article","header","footer","h1","h2","h3","h4","h5","h6","blockquote","pre","ul","ol","li","dl","dt","dd","tr");
    private static final Set<String> STYLES=Set.of("p","h1","h2","h3","h4","h5","h6","blockquote","pre","ul","ol","li","strong","b","em","i","code","sup","sub");
    private static final Set<String> HIDDEN=Set.of("head","script","style","form","iframe","object","template","noscript","svg","math");
    private record Style(String tag,int start,int end,int order) { }
    private record Open(String tag,int start,int order) { }
    private record Image(int start,String path,String alt) { }
    private final StringBuilder text=new StringBuilder();
    private final PriorityQueue<Style> styles=new PriorityQueue<>(Comparator.comparingInt(Style::order).reversed());
    private final List<Image> images=new ArrayList<>();
    private int points,anchor=-1,styleCount,selection;
    private boolean whitespace;
    int total(){return points;}
    int anchor(){return anchor;}
    private void boundary()throws IOException{if(points>0 && text.charAt(text.length()-1)!='\n')append("\n",false);whitespace=false;}
    private void append(String value,boolean collapse)throws IOException {
        for(int i=0;i<value.length();) {
            int cp=value.codePointAt(i);i+=Character.charCount(cp);
            if(collapse && Character.isWhitespace(cp)){whitespace=points>0 && text.charAt(text.length()-1)!='\n';continue;}
            if(whitespace){text.append(' ');points++;whitespace=false;}
            if(Character.isISOControl(cp) && cp!='\n' && cp!='\t')continue;
            text.appendCodePoint(cp);points++;
            if(text.length()>8*1024*1024)throw new IOException("Chapter limit");
        }
    }
    static ChapterText parse(byte[] bytes,String requestedAnchor,ReaderBudget budget)throws IOException {
        return parse(bytes,requestedAnchor,0,budget);
    }
    static ChapterText parse(byte[] bytes,String requestedAnchor,int offset,ReaderBudget budget)throws IOException {
        var result=new ChapterText();result.selection=requestedAnchor==null?offset:Integer.MAX_VALUE;var stack=new ArrayDeque<Open>();
        int[] hidden={0},body={0},pre={0};
        ReaderXml.read(bytes,budget,(r,depth)->{
            if(r.isStartElement()) {
                String tag=r.getLocalName();boolean xhtml="http://www.w3.org/1999/xhtml".equals(r.getNamespaceURI());
                if(depth==1 && (!xhtml || !tag.equals("html")))throw new IOException("Invalid XHTML");
                if(hidden[0]>0)return;
                if(!xhtml || HIDDEN.contains(tag)){if(body[0]>0 && tag.equals("svg"))result.append("[Изображение недоступно]",false);hidden[0]=depth;return;}
                if(tag.equals("body"))body[0]=depth;
                if(tag.equals("pre"))pre[0]++;
                if(body[0]==0)return;
                if(BLOCKS.contains(tag))result.boundary();
                if(requestedAnchor!=null && requestedAnchor.equals(ReaderXml.attr(r,"id")) && result.anchor<0){result.anchor=result.points;result.selection=result.points;}
                if(STYLES.contains(tag))stack.push(new Open(tag,result.points,result.styleCount++));
                else stack.push(new Open("",result.points,-1));
                if(tag.equals("br"))result.boundary();
                if(tag.equals("img")) {
                    String alt=ReaderXml.attr(r,"alt");if(alt==null || alt.isBlank())alt="Изображение";
                    alt=alt.codePoints().limit(200).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
                    int start=result.points;result.append("["+alt+"]",true);
                    if(start>=result.selection && (long)start< (long)result.selection+16384 && result.images.size()<128)result.images.add(new Image(start,ReaderXml.attr(r,"src"),alt));
                }
                if(Set.of("video","audio","canvas","embed").contains(tag))result.append("[Медиа недоступно]",false);
            }else if(r.isEndElement()) {
                if(hidden[0]>0){if(hidden[0]==depth)hidden[0]=0;return;}
                if(body[0]==0)return;
                var open=stack.pop();
                if(open.order>=0 && result.points>open.start && result.points>result.selection && (long)open.start<(long)result.selection+16384) {
                    var style=new Style(open.tag,open.start,result.points,open.order);
                    // Keep earliest opening tags in the selected fragment, so retained children retain parents.
                    if(result.styles.size()<256)result.styles.add(style);
                    else if(style.order<result.styles.peek().order){result.styles.poll();result.styles.add(style);}
                }
                if(BLOCKS.contains(r.getLocalName()))result.boundary();
                if(r.getLocalName().equals("pre"))pre[0]--;
                if(body[0]==depth)body[0]=0;
            }else if(r.isCharacters() && body[0]>0 && hidden[0]==0)result.append(r.getText(),pre[0]==0);
        });
        return result;
    }
    String render(int offset,int limit,Function<String,String> imageUrl) {
        if(points==0 && offset==0)return "";
        int end=Math.min(points,offset+limit);if(offset<0 || offset>=points)throw new IllegalArgumentException("Invalid fragment range");
        String part=text.substring(text.offsetByCodePoints(0,offset),text.offsetByCodePoints(0,end));
        int[] indices=new int[end-offset+1];int index=0;for(int i=0;i<indices.length-1;i++){indices[i]=index;index+=Character.charCount(part.codePointAt(index));}indices[indices.length-1]=index;
        var boundaries=new TreeSet<Integer>();boundaries.add(offset);boundaries.add(end);
        for(var s:styles)if(s.start<end && s.end>offset){boundaries.add(Math.max(offset,s.start));boundaries.add(Math.min(end,s.end));}
        for(var image:images)if(image.start>=offset && image.start<end)boundaries.add(image.start);
        var html=new StringBuilder();var active=new ArrayList<Style>();var positions=new ArrayList<>(boundaries);
        for(int i=0;i<positions.size()-1;i++) {
            int position=positions.get(i),next=positions.get(i+1);
            var needed=styles.stream().filter(s->s.start<=position && s.end>position).sorted(Comparator.comparingInt(Style::order)).toList();
            int common=0;while(common<active.size() && common<needed.size() && active.get(common).equals(needed.get(common)))common++;
            for(int j=active.size()-1;j>=common;j--)html.append("</").append(active.get(j).tag).append('>');
            for(int j=common;j<needed.size();j++)html.append('<').append(needed.get(j).tag).append('>');
            active=new ArrayList<>(needed);
            String slice=part.substring(indices[position-offset],indices[next-offset]);
            boolean listContainer=!active.isEmpty() && Set.of("ul","ol").contains(active.getLast().tag);
            if(listContainer && slice.isBlank()){html.append(slice);continue;}
            if(listContainer)html.append("<li>");
            for(var image:images)if(image.start==position && image.path!=null) {
                String url=imageUrl.apply(image.path);
                if(url!=null)html.append("<img loading=\"lazy\" src=\"").append(HtmlUtils.htmlEscape(url)).append("\" alt=\"").append(HtmlUtils.htmlEscape(image.alt)).append("\">");
            }
            boolean separator=slice.equals("\n") && styles.stream().anyMatch(s->BLOCKS.contains(s.tag) && (s.end==position || s.start==next));
            html.append(separator?"<span class=\"reader-separator\" data-offset=\"":"<span data-offset=\"").append(position).append("\">").append(HtmlUtils.htmlEscape(slice)).append("</span>");
            if(listContainer)html.append("</li>");
        }
        for(int j=active.size()-1;j>=0;j--)html.append("</").append(active.get(j).tag).append('>');
        return html.length()>262144?HtmlUtils.htmlEscape(part):html.toString();
    }
}
