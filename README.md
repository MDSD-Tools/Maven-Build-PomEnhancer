# Pom-Enhancer-Maven-Plugin
The pom-enhancer-maven-plugin is a maven plugin to support publishing tycho-built artifacts to m2 repositories, in particular, Maven Central.

The Eclipse CBI aggregator is able to convert a tycho-built updatesite into a Maven-compatible repository. Doing so, it generates a synthetic pom.xml for each artifact. In the process it converts bundle-dependencies to traditional maven dependencies.

In order to publish the artifacts to Maven Central the generated pom.xml is required to have certain information, which is not filled in by the CBI aggregator.

The pom-enhancer-maven-plugin post-processes the generated pom.xml-files and adds the missing meta-data based on a template pom.

The following excerpt shows an exemplary configuration. In this case, the multi-project root pom constitutes the pom-template.

```XML
    <build>
        <plugins>
            <plugin>
                <groupId>tools.mdsd</groupId>
                <artifactId>pom-enhancer-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>enhance-pom</goal>
                        </goals>
                        <phase>package</phase>
                    </execution>
                </executions>
                <configuration>
                    <scanDir>
                        <fileSet>
                            <directory>${project.build.directory}/m2/final/</directory>
                            <includes>
                                <include>**/*.pom</include>
                            </includes>
                        </fileSet>
                    </scanDir>
                    <pomTemplate>${maven.multiModuleProjectDirectory}/pom.xml</pomTemplate>
                    <requiredFields>description,scm,licenses,name,url,developers</requiredFields>
                </configuration>
            </plugin>
        </plugins>
    </build>
``` 