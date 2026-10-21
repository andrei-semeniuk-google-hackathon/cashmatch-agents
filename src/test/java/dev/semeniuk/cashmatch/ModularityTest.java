package dev.semeniuk.cashmatch;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Fails the build when a module reaches into another module's internals or a cycle appears. */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(CashmatchApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void writesModuleDiagrams() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
