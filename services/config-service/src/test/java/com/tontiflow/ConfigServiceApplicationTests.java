package com.tontiflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// "native" doit rester actif en plus de "test" : @ActiveProfiles remplace la
// liste des profils actifs plutot que de s'y ajouter, et c'est le profil
// "native" qui selectionne le backend filesystem du Config Server (voir
// application.yml). Sans lui, le serveur retombe sur le backend Git par
// defaut et echoue faute d'URI de depot configuree (constat reel).
@SpringBootTest
@ActiveProfiles({"test", "native"})
class ConfigServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
