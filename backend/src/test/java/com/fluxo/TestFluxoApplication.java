package com.fluxo;

import org.springframework.boot.SpringApplication;

public class TestFluxoApplication {

	public static void main(String[] args) {
		SpringApplication.from(FluxoApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
