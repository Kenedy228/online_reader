package com.example.online_reader.book.files;

import com.example.online_reader.user.model.User;
import com.example.online_reader.user.repository.UserRepository;
import com.example.online_reader.book.repository.BookRepository;
import com.example.online_reader.book.model.Book;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookFilesHttpTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired BookRepository books;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookFileCleanup cleanup;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PrivateBookStorage storage;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean BookFormatValidator validator;
    @Autowired FileBookTransactions transactions;
    HttpClient client;
    CookieManager cookies;
    String token;
    User owner;
    @BeforeAll void login()throws Exception {
        owner=users.saveAndFlush(new User("upload-"+UUID.randomUUID(),encoder.encode("Synthetic-upload-password")));
        cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        authenticate(client,owner.getUsername());token=csrf(get(client,"/library/upload").body());
    }
    String base(){return "http://127.0.0.1:"+port;}
    HttpResponse<String> get(HttpClient c,String path)throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    void authenticate(HttpClient c,String name)throws Exception {
        var login=get(c,"/login");String csrf=URLEncoder.encode(csrf(login.body()),StandardCharsets.UTF_8);
        var response=c.send(HttpRequest.newBuilder(URI.create(base()+"/login")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf="+csrf+"&username="+name+"&password=Synthetic-upload-password")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
    }
    String csrf(String html){var matcher=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);assertThat(matcher.find()).isTrue();return matcher.group(1);}
    byte[] multipart(String filename,byte[] data)throws IOException {
        var out=new ByteArrayOutputStream();out.write(("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(data);out.write("\r\n--boundary--\r\n".getBytes(StandardCharsets.UTF_8));return out.toByteArray();
    }
    HttpResponse<String> upload(byte[] data,String name,String csrf)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base()+"/library/upload")).timeout(Duration.ofSeconds(45)).header("Content-Type","multipart/form-data; boundary=boundary");
        if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(name,data))).build(),HttpResponse.BodyHandlers.ofString());
    }
    String createText()throws Exception {
        var response=upload(("Синтетический текст "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),"Книга.txt",token);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(303);
        return URI.create(response.headers().firstValue("Location").orElseThrow()).getPath();
    }
    @Test void actualTxtAndEpubUploadDownloadHavePrivateHeadersAndNoInlineExecution()throws Exception {
        String content="<script>alert('immutable-source')</script> "+UUID.randomUUID();
        var response=upload(content.getBytes(StandardCharsets.UTF_8),"Текст.txt",token);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(303);
        String path=URI.create(response.headers().firstValue("Location").orElseThrow()).getPath();
        var download=get(client,path+"/download");
        assertThat(download.statusCode()).isEqualTo(200);assertThat(download.body()).isEqualTo(content);
        assertThat(download.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;").contains("book-").doesNotContain("Текст");
        assertThat(download.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        assertThat(download.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(download.headers().firstValue("Content-Security-Policy").orElseThrow()).doesNotContain("unsafe-inline");
        Path epub=Files.createTempFile("synthetic-epub-",".epub");
        try {
            EpubFixtures.write(epub,EpubFixtures.content());var accepted=upload(Files.readAllBytes(epub),"Книга.epub",token);
            assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(303);
            var bytes=client.send(HttpRequest.newBuilder(URI.create(base()).resolve(accepted.headers().firstValue("Location").orElseThrow()+"/download")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(bytes.statusCode()).isEqualTo(200);assertThat(bytes.body()).isEqualTo(Files.readAllBytes(epub));
            assertThat(bytes.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/epub+zip");
        }finally{Files.deleteIfExists(epub);}
    }
    @Test void duplicatesAreScopedAndDoNotAllocateSecondRecord()throws Exception {
        byte[] content=("Duplicate "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        var first=upload(content,"first.txt",token);assertThat(first.statusCode()).isEqualTo(303);
        long before=books.countByOwner_Id(owner.getId());
        var duplicate=upload(content,"second.txt",token);assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("upload-duplicate");assertThat(books.countByOwner_Id(owner.getId())).isEqualTo(before);
        String path=URI.create(first.headers().firstValue("Location").orElseThrow()).getPath();long id=Long.parseLong(path.substring(path.lastIndexOf('/')+1));
        jdbc.update("update books set state='PROCESSING' where id=?",id);
        var processing=upload(content,"third.txt",token);assertThat(processing.statusCode()).isEqualTo(409);assertThat(processing.body()).doesNotContain("id=\"upload-duplicate\"");
        jdbc.update("update books set state='DELETING' where id=?",id);
        var deleting=upload(content,"third.txt",token);assertThat(deleting.statusCode()).isEqualTo(409);assertThat(deleting.body()).doesNotContain("id=\"upload-duplicate\"");
    }
    @Test void foreignUserAndAdminCannotReadSourceOrPublicStoragePath()throws Exception {
        String path=createText();
        for(boolean admin:List.of(false,true)) {
            var other=users.saveAndFlush(new User("outsider-"+UUID.randomUUID(),encoder.encode("Synthetic-upload-password")));
            if(admin)jdbc.update("update users set role='Admin' where id=?",other.getId());
            var foreign=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();authenticate(foreign,other.getUsername());
            assertThat(get(foreign,path+"/download").statusCode()).isEqualTo(404);
            assertThat(get(foreign,"/library/books/9223372036854775807/download").statusCode()).isEqualTo(404);
            assertThat(get(foreign,"/sources/random.book").statusCode()).isEqualTo(403);
        }
    }
    @Test void invalidCsrfNamesFormatsBinaryAndSizeFailBeforeReady()throws Exception {
        byte[] content=("safe "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        assertThat(upload(content,"safe.txt",null).statusCode()).isEqualTo(403);
        assertThat(upload(content,"safe.txt","invalid").statusCode()).isEqualTo(403);
        for(String name:List.of("../escape.txt","C:\\escape.txt","/abs.txt"))assertThat(upload(content,name,token).statusCode()).isEqualTo(422);
        assertThat(upload(content,"unsupported.pdf",token).statusCode()).isEqualTo(415);
        assertThat(upload(new byte[]{0},"binary.txt",token).statusCode()).isEqualTo(422);
        assertThat(upload(new byte[10*1024*1024+1],"oversize.txt",token).statusCode()).isEqualTo(413);
    }
    @Test void confirmedDeleteRemovesSourceAndMetadataAndRetryIsSafe()throws Exception {
        String path=createText();long id=Long.parseLong(path.substring(path.lastIndexOf('/')+1));
        String deleteToken=csrf(get(client,path+"/delete").body());
        var deleted=client.send(HttpRequest.newBuilder(URI.create(base()+path+"/delete")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf="+URLEncoder.encode(deleteToken,StandardCharsets.UTF_8)+"&version=1&confirmed=true")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).withFailMessage(deleted.body()).isEqualTo(302);
        assertThat(get(client,path+"/download").statusCode()).isEqualTo(404);
        for(int i=0;i<3;i++)cleanup.retry();
        assertThat(books.findById(id)).isEmpty();
    }
    @Test void admissionIsBoundedBeforeBodyParsingAndSlowBodyTimesOut()throws Exception {
        long started=System.nanoTime();
        try(var first=slowBody();var second=slowBody()) {
            int status=0;
            for(int i=0;i<30;i++) {
                status=upload(new byte[0],"empty.txt",token).statusCode();
                if(status==503)break;Thread.sleep(50);
            }
            assertThat(status).isEqualTo(503);
            second.close();
            // Keep each gap below the existing socket idle timeout: test the absolute deadline,
            // not the container's independent (earlier) idle-connection shutdown.
            try(var trickle=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
                trickle.scheduleAtFixedRate(()->{try{first.getOutputStream().write("1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));first.getOutputStream().flush();}catch(IOException closed){ }},1,2,java.util.concurrent.TimeUnit.SECONDS);
            var input=new BufferedReader(new InputStreamReader(first.getInputStream(),StandardCharsets.US_ASCII));
            assertThat(input.readLine()).contains("408");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(28),Duration.ofSeconds(38));
            }
        }
        String next=null;
        for(int i=0;i<30;i++) {
            var response=upload(("After timeout "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),"after-timeout.txt",token);
            if(response.statusCode()==303){next=response.headers().firstValue("Location").orElseThrow();break;}
            assertThat(response.statusCode()).isEqualTo(503);Thread.sleep(50);
        }
        assertThat(next).isNotNull();
    }
    Socket slowBody()throws IOException {
        var socket=new Socket("127.0.0.1",port);socket.setSoTimeout(40000);
        String session=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        String headers="POST /library/upload HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nCookie: JSESSIONID="+session+"\r\nX-CSRF-TOKEN: "+token+"\r\nContent-Type: multipart/form-data; boundary=boundary\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabc\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();return socket;
    }

    @Test void processingDeadlineReturnsControlledFailureAndWorkerCapacityRecovers()throws Exception {
        org.mockito.Mockito.doAnswer(invocation->{
            try{Thread.sleep(30000);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
            ((ProcessingBudget)invocation.getArgument(2)).check();return null;
        }).when(validator).validate(org.mockito.ArgumentMatchers.any(Path.class),org.mockito.ArgumentMatchers.any(Book.Format.class),org.mockito.ArgumentMatchers.any(ProcessingBudget.class));
        long started=System.nanoTime();
        var result=upload(("Timeout "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),"timeout.txt",token);
        assertThat(result.statusCode()).isEqualTo(422);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(8),Duration.ofSeconds(14));
        org.mockito.Mockito.doCallRealMethod().when(validator).validate(org.mockito.ArgumentMatchers.any(Path.class),org.mockito.ArgumentMatchers.any(Book.Format.class),org.mockito.ArgumentMatchers.any(ProcessingBudget.class));
        assertThat(createText()).startsWith("/library/books/");
    }
    @Test void cleanupIoFailureRetainsSourceAndQuotaUntilActualRetrySucceeds()throws Exception {
        String path=createText();long id=Long.parseLong(path.substring(path.lastIndexOf('/')+1));var key=books.findById(id).orElseThrow().getStorageKey();
        org.mockito.Mockito.doThrow(new IOException("synthetic cleanup failure")).when(storage).removeIfIdle(key);
        String deleteToken=csrf(get(client,path+"/delete").body());
        client.send(HttpRequest.newBuilder(URI.create(base()+path+"/delete")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf="+URLEncoder.encode(deleteToken,StandardCharsets.UTF_8)+"&version=1&confirmed=true")).build(),HttpResponse.BodyHandlers.ofString());
        for(int i=0;i<3;i++)cleanup.retry();
        var marker=books.findById(id).orElseThrow();assertThat(marker.getState()).isEqualTo(Book.State.DELETING);
        try(var source=storage.open(key,marker.getOriginalSize())){assertThat(source.readAllBytes()).isNotEmpty();}
        assertThat(get(client,path+"/download").statusCode()).isEqualTo(404);
        org.mockito.Mockito.doCallRealMethod().when(storage).removeIfIdle(key);
        for(int i=0;i<3;i++)cleanup.retry();assertThat(books.findById(id)).isEmpty();
    }

}
