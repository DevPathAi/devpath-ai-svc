package ai.devpath.aigw;

import ai.devpath.shared.error.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(ApiExceptionHandler.class) // 스펙 §3.4 공통 에러 envelope(공용 advice). Ollama 전용은 OllamaExceptionHandler 유지.
public class AiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiApplication.class, args);
	}

}

