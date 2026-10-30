package com.cellforge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.cellforge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule cellsAndEdgeShareOnlyTheSharedPackage = slices()
        .matching("com.cellforge.(cell|edge)..").should().notDependOnEachOther()
        .because("cells and the edge are deployed separately and talk only over HTTP and Kafka");

    @ArchTest
    static final ArchRule sharedKnowsNothingOfEitherSide = noClasses()
        .that().resideInAPackage("com.cellforge.shared..")
        .should().dependOnClassesThat().resideInAnyPackage("com.cellforge.cell..", "com.cellforge.edge..");

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
            "com.fasterxml..", "..application..", "..adapter..", "..config..");

    @ArchTest
    static final ArchRule applicationDoesNotReachIntoAdapters = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..", "org.springframework.web..",
            "org.springframework.jdbc..", "org.springframework.kafka..", "org.springframework.data.redis..");

    @ArchTest
    static final ArchRule adaptersOnlyUseTheirOwnModulesAdapters = slices()
        .matching("com.cellforge.(*).(*).adapter..").should().notDependOnEachOther()
        .because("a module reaches another module through its application ports, not its adapters");

    @ArchTest
    static final ArchRule edgeModulesHaveNoCycles = slices()
        .matching("com.cellforge.edge.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule onlyTheTenancyModuleTouchesTheRowLevelSecuritySetup = classes()
        .that().haveSimpleName("RlsTransactionManager")
        .should().onlyBeAccessed().byClassesThat().resideInAnyPackage("com.cellforge.cell.tenancy..", "com.cellforge.cell.config..");
}
