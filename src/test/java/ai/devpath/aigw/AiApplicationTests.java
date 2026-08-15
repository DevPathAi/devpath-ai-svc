package ai.devpath.aigw;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiApplicationTests {

	@Autowired FlywayProperties flywayProperties;

	@Test
	void contextLoads() {
	}

	@Test
	void concurrentIndexMigrationsUseSessionLevelFlywayLock() {
		assertThat(flywayProperties.getPostgresql().getTransactionalLock()).isFalse();
	}

}

