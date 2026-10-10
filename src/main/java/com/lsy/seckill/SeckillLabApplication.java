package com.lsy.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SeckillLabApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeckillLabApplication.class, args);
	}

}
