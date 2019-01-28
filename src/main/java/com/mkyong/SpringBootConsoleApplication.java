package com.mkyong;

import com.mkyong.service.HelloMessageService;
import com.mkyong.service.JavaHttpUrlConnectionReader;
import com.mkyong.service.hlsM3u8Parser;
import okhttp3.HttpUrl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

import static java.lang.System.exit;

@SpringBootApplication
public class SpringBootConsoleApplication implements CommandLineRunner {
    private static final String URL_STR = "http://c13.prod.playlists.ihrhls.com:80/1713/playlist.m3u8?listeningSessionID=5b9c08c34a7a49cd_11610354_x53gH5XK__000000706Tz&downloadSessionID=0";
    private static final HttpUrl URL = HttpUrl.parse(URL_STR);

    @Autowired
    private HelloMessageService helloService;

    public static void main(String[] args) throws Exception {

        //disabled banner, don't want to see the spring logo
        SpringApplication app = new SpringApplication(SpringBootConsoleApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.run(args);

        //SpringApplication.run(SpringBootConsoleApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {

        if (args.length > 0 ) {
            System.out.println(helloService.getMessage(args[0].toString()));
        }else{
            System.out.println(helloService.getMessage());
        }

try {
     String results = JavaHttpUrlConnectionReader.doHttpUrlConnectionAction(URL_STR);
//    String results = "#EXTINF:3,title=\"Woody Sander Ford\",artist=\"Dc\",url=\"song_spot=\"T\" MediaBaseId=\"-1\" itunesTrackId=\"0\" amgTrackId=\"-1\" amgArtistId=\"0\" TAID=\"-1\" TPID=\"-1\" cartcutId=\"7285969001\" amgArtworkURL=\"\" length=\"00:00:17\" unsID=\"-1\" spotInstanceId=\"-1\"";

    InputStream inputStream = new ByteArrayInputStream(results.getBytes(Charset.forName("UTF-8")));
    final hlsM3u8Parser hlsM3u8Parser = new hlsM3u8Parser(inputStream, URL.url(), true);

} catch (Exception e) {
    System.out.println(e.getMessage());
    throw e;
}


        exit(0);
    }
}