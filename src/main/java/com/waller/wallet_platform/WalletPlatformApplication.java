package com.waller.wallet_platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WalletPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(WalletPlatformApplication.class, args);
	}

}
