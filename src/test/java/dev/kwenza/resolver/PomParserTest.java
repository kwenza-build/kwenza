package dev.kwenza.resolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomParserTest {

    @Test
    void readsGroupArtifactVersionAndPackaging() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>33.2.1-jre</version>
                  <packaging>jar</packaging>
                </project>
                """);

        assertEquals("com.google.guava", pom.group());
        assertEquals("guava", pom.artifact());
        assertEquals("33.2.1-jre", pom.version());
        assertEquals("jar", pom.packaging());
        assertNull(pom.parent());
    }

    @Test
    void packagingDefaultsToJarWhenAbsent() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>thing</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertEquals("jar", pom.packaging());
    }

    @Test
    void groupAndVersionAreInheritedFromParentWhenAbsent() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <parent>
                    <groupId>com.example.parent</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>2.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """);

        assertEquals("com.example.parent", pom.group());
        assertEquals("2.0", pom.version());
        assertEquals("com.example.parent", pom.parent().group());
        assertEquals("parent-pom", pom.parent().artifact());
        assertEquals("2.0", pom.parent().version());
    }

    @Test
    void anExplicitGroupIdOverridesTheParentsEvenWhenAParentIsPresent() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <parent>
                    <groupId>com.example.parent</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>2.0</version>
                  </parent>
                  <groupId>com.example.child</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertEquals("com.example.child", pom.group());
        assertEquals("1.0", pom.version());
    }

    @Test
    void anArtifactIdWithNoValueAtAllThrowsAResolutionException() {
        assertThrows(ResolutionException.class, () -> PomParser.parse("<project></project>"));
    }

    @Test
    void propertiesAreCollectedAsAFlatMap() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <artifactId>x</artifactId>
                  <properties>
                    <guava.version>33.2.1-jre</guava.version>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);

        assertEquals("33.2.1-jre", pom.properties().get("guava.version"));
        assertEquals("21", pom.properties().get("maven.compiler.release"));
    }

    @Test
    void dependenciesDefaultToCompileScopeAndNonOptional() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <artifactId>x</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>a</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>b</artifactId>
                      <version>2.0</version>
                      <scope>test</scope>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>
                </project>
                """);

        List<PomParser.RawDependency> deps = pom.dependencies();
        assertEquals(2, deps.size());
        assertEquals("compile", deps.get(0).scope());
        assertFalse(deps.get(0).optional());
        assertEquals("test", deps.get(1).scope());
        assertTrue(deps.get(1).optional());
    }

    @Test
    void managedDependenciesAreParsedSeparatelyFromRegularDependencies() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <artifactId>x</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>managed</artifactId>
                        <version>5.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>direct</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertEquals(1, pom.dependencies().size());
        assertEquals("direct", pom.dependencies().get(0).artifact());
        assertEquals(1, pom.managedDependencies().size());
        assertEquals("managed", pom.managedDependencies().get(0).artifact());
    }

    @Test
    void aManagedDependencyVersionCanBeOmitted() {
        PomParser.ParsedPom pom = PomParser.parse("""
                <project>
                  <artifactId>x</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>a</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertNull(pom.dependencies().get(0).version());
    }
}
