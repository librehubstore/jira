package com.fluxo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FluxoApplication {

	public static void main(String[] args) {
		SpringApplication.run(FluxoApplication.class, args);
	}

}
