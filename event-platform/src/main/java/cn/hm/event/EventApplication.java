package cn.hm.event;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("cn.hm.event.persistence")
@EnableScheduling
public class EventApplication {
    public static void main(String[] args) { SpringApplication.run(EventApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
