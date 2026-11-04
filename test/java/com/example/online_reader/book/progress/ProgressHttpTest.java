package com.example.online_reader.book.progress;

import com.example.online_reader.book.files.*;
import com.example.online_reader.book.model.Book;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressHttpTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;@Autowired BookRepository books;@Autowired ProgressRepository progress;
    @Autowired PasswordEncoder encoder;@Autowired PrivateBookStorage storage;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    HttpClient client;CookieManager cookies;User owner;String token;
    @BeforeAll void login()throws Exception {
        owner=users.saveAndFlush(new User("progress-"+UUID.randomUUID(),encoder.encode("Synthetic-progress-password")));
        cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();authenticate(client,owner.getUsername());
        token=attribute(get(client,"/library").body(),"name=\"_csrf\"[^>]*value");
    }
    String base(){return "http://127.0.0.1:"+port;}
    HttpResponse<String> get(HttpClient c,String path)throws Exception{return c.send(HttpRequest.newBuilder(URI.create(base()+path)).header("Accept",path.startsWith("/api/")?"application/json":"text/html").timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    void authenticate(HttpClient c,String name)throws Exception {
        String csrf=attribute(get(c,"/login").body(),"name=\"_csrf\"[^>]*value");
        var response=c.send(HttpRequest.newBuilder(URI.create(base()+"/login")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf="+URLEncoder.encode(csrf,StandardCharsets.UTF_8)+"&username="+name+"&password=Synthetic-progress-password")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
    }
    String attribute(String html,String name){var m=Pattern.compile(name+"=\"([^\"]*)\"").matcher(html);assertThat(m.find()).isTrue();return org.springframework.web.util.HtmlUtils.htmlUnescape(m.group(1));}
    Book book()throws Exception {
        byte[] bytes=("Книга "+UUID.randomUUID()+"\r\n"+"а🙂б\r\n".repeat(2000)).getBytes(StandardCharsets.UTF_8);var key=UUID.randomUUID();
        var b=Book.processing(owner,"Книга","Автор",Book.Format.TXT,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),key);b.markReady();b=books.saveAndFlush(b);
        var root=(Path)org.springframework.test.util.ReflectionTestUtils.getField(storage,"root");Files.write(root.resolve("sources").resolve(key+".book"),bytes);return b;
    }
    String api(Book b){return "/api/v1/books/"+b.getId()+"/reading-progress";}
    String reader(Book b){return "/library/books/"+b.getId()+"/read";}
    String proof(Book b)throws Exception {return attribute(get(client,reader(b)).body(),"data-proof");}
    ReadingPosition position(Book b,int offset){return new ReadingPosition("text",offset,b.getSourceSha256(),1);}
    String body(Book b,int offset,int revision,String proof)throws Exception{return json.writeValueAsString(new ProgressController.Save(revision,position(b,offset),proof));}
    HttpResponse<String> put(HttpClient c,Book b,String body,String csrf)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base()+api(b))).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("Accept","application/json");
        if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);
        return c.send(request.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void savesRestoresAcrossFreshSessionAndUsesServerRevisionInsteadOfClientTime()throws Exception {
        var b=book();String proof=proof(b);assertThat(json.readTree(get(client,api(b)).body()).path("revision").asInt()).isZero();
        var created=put(client,b,body(b,5,0,proof),token);assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        assertThat(json.readTree(created.body()).path("revision").asInt()).isEqualTo(1);
        var stale=put(client,b,body(b,9,0,proof),token);assertThat(stale.statusCode()).isEqualTo(409);assertThat(stale.body()).contains("progress_conflict");
        assertThat(put(client,b,body(b,9,1,proof),token).statusCode()).isEqualTo(200);
        var fresh=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();authenticate(fresh,owner.getUsername());
        assertThat(attribute(get(fresh,reader(b)).body(),"data-offset")).isEqualTo("9");
        assertThat(attribute(get(fresh,reader(b)+"?offset=0").body(),"data-offset")).isEqualTo("0");
        jdbc.update("update books set title='Новое имя',version=version+1 where id=?",b.getId());
        assertThat(json.readTree(get(client,api(b)).body()).path("position").path("offset").asInt()).isEqualTo(9);
    }
    @Test void rejectsForgedOwnerFieldsInvalidPositionsAndAmbiguousJsonWithoutWriting()throws Exception {
        var b=book();String proof=proof(b),valid=body(b,0,0,proof);
        for(String malformed:List.of(valid.substring(0,valid.length()-1)+",\"owner_id\":7}",valid+" {}",valid.replace("\"revision\":0","\"revision\":0,\"revision\":1"),
                valid.replace("\"revision\":0","\"revision\":0.9"),valid.replace("\"revision\":0","\"revision\":\"0\""),
                valid.replace("\"offset\":0","\"offset\":12.9"),valid.replace("\"version\":1","\"version\":1.9"))) {
            var result=put(client,b,malformed,token);assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(400);
        }
        assertThat(put(client,b,body(b,-1,0,proof),token).statusCode()).isEqualTo(422);
        assertThat(put(client,b,body(b,10485760,0,proof),token).statusCode()).isEqualTo(409);
        assertThat(put(client,b,body(b,0,0,"forged"),token).body()).contains("stale_reader");
        assertThat(put(client,b,valid.replace(b.getSourceSha256(),"0".repeat(64)),token).body()).contains("stale_position");
        assertThat(progress.findByBookIdAndOwnerId(b.getId(),owner.getId())).isEmpty();
    }
    @Test void userAdminAndInactiveSessionCannotReadOrMutateForeignProgress()throws Exception {
        var b=book();String valid=body(b,3,0,proof(b));
        for(boolean admin:List.of(false,true)) {
            var other=users.saveAndFlush(new User("other-"+UUID.randomUUID(),encoder.encode("Synthetic-progress-password")));
            if(admin)jdbc.update("update users set role='Admin' where id=?",other.getId());
            var c=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();authenticate(c,other.getUsername());String csrf=attribute(get(c,"/library").body(),"name=\"_csrf\"[^>]*value");
            var foreign=get(c,api(b));var missing=get(c,"/api/v1/books/9223372036854775807/reading-progress");
            assertThat(foreign.statusCode()).isEqualTo(404);assertThat(missing.body()).isEqualTo(foreign.body());
            assertThat(put(c,b,valid,csrf).statusCode()).isEqualTo(404);
        }
        jdbc.update("update users set enabled=false where id=?",owner.getId());
        try{assertThat(get(client,api(b)).statusCode()).isEqualTo(403);assertThat(put(client,b,valid,token).statusCode()).isEqualTo(403);}
        finally{jdbc.update("update users set enabled=true where id=?",owner.getId());}
        assertThat(progress.findByBookIdAndOwnerId(b.getId(),owner.getId())).isEmpty();
    }
    @Test void nativeCsrfAuthenticationAndJsonReceiveLimitsApplyBeforeMutation()throws Exception {
        var b=book();String valid=body(b,0,0,proof(b));
        assertThat(get(HttpClient.newHttpClient(),api(b)).statusCode()).isEqualTo(401);
        assertThat(put(client,b,valid,null).statusCode()).isEqualTo(403);
        assertThat(put(client,b,valid,"bad").statusCode()).isEqualTo(403);
        assertThat(put(client,b," ".repeat(2049),token).statusCode()).isEqualTo(413);
        var chunked=HttpRequest.newBuilder(URI.create(base()+api(b))).header("Content-Type","application/json").header("Accept","application/json").header("X-CSRF-TOKEN",token)
                .PUT(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(new byte[2049]))).build();
        assertThat(client.send(chunked,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        var form=HttpRequest.newBuilder(URI.create(base()+api(b))).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",token).PUT(HttpRequest.BodyPublishers.ofString("position=0")).build();
        assertThat(client.send(form,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        assertThat(progress.findByBookIdAndOwnerId(b.getId(),owner.getId())).isEmpty();
    }
    @Test void confirmedLogicalDeleteRemovesProgressInItsFirstCommitAndRefusesLaterSave()throws Exception {
        var b=book();String valid=body(b,3,0,proof(b));assertThat(put(client,b,valid,token).statusCode()).isEqualTo(201);
        String deletion=attribute(get(client,"/library/books/"+b.getId()+"/delete").body(),"name=\"_csrf\"[^>]*value");
        var result=client.send(HttpRequest.newBuilder(URI.create(base()+"/library/books/"+b.getId()+"/delete")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf="+URLEncoder.encode(deletion,StandardCharsets.UTF_8)+"&confirmed=true&version=0")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(302);assertThat(progress.findByBookIdAndOwnerId(b.getId(),owner.getId())).isEmpty();
        assertThat(get(client,api(b)).statusCode()).isEqualTo(404);assertThat(put(client,b,valid,token).statusCode()).isEqualTo(404);
    }
    @Test void writeRateBudgetIsBoundedPerAccountAfterCsrf()throws Exception {
        var user=users.saveAndFlush(new User("rate-"+UUID.randomUUID(),encoder.encode("Synthetic-progress-password")));
        var c=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();authenticate(c,user.getUsername());
        String csrf=attribute(get(c,"/library").body(),"name=\"_csrf\"[^>]*value");var b=book();
        for(int i=0;i<100;i++)assertThat(put(c,b,"{",csrf).statusCode()).isEqualTo(400);
        var denied=put(c,b,"{",csrf);assertThat(denied.statusCode()).isEqualTo(429);assertThat(denied.headers().firstValue("Retry-After")).isPresent();
        assertThat(denied.body()).contains("rate_limited");
    }
    @Test void unfinishedJsonIsRejectedByTotalDeadlineAndServerKeepsServing()throws Exception {
        var b=book();long started=System.nanoTime();
        try(var socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(9000);
            String cookie=cookies.getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
            String headers="PUT "+api(b)+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nContent-Type: application/json\r\nAccept: application/json\r\nCookie: "+cookie+"\r\nX-CSRF-TOKEN: "+token+"\r\nContent-Length: 100\r\n\r\n{";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            String status=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();assertThat(status).contains("408");
        }
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(4),Duration.ofSeconds(9));
        assertThat(get(client,api(b)).statusCode()).isEqualTo(200);
    }
}
