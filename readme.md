## 项目差异性解决

### 背景 ###

由于历史遗留问题，很多项目没有使用远程配置中心(disconf)。比如商务，客服等等，maven打包后将完成项目配置替换,主要将${}替换为真实值。

### 问题 ###

项目中filter过多，维护不方便。同时为了部分的安全，线上配置很多需要配置到线上对应的mavan的setting中。

### simpile解决方案 ###

simpile解决方案为：
	
	配置文件托管给disconf

优点：

所有配置全由disconf托管

缺点：

1. 不支持disconf动态修改

也就说我们仅仅支持将文件托管给disconf管理。

这是由于，只是兼容历史遗留项目，新项目则更应该使用配置中心机制。


#### 如何配置 ####

pom

        <dependency>
		    <groupId>com.codeL.disconf</groupId>
		    <artifactId>disconf-client</artifactId>
		    <version>0.0.3-SNAPSHOT</verison>
        </dependency>

web.xml 

    <listener>
        <listener-class>com.codeL.disconf.remote.deploy.DeployListener</listener-class>
    </listener>
    <context-param>
        <param-name>enable_remote</param-name>
        <param-value>true</param-value>
    </context-param>
    <context-param>
        <param-name>repositoryURL</param-name>
        <param-value>http://localhost/url</param-value>
    </context-param>
    <context-param>
        <param-name>ignoreFiles</param-name>
        <param-value>Log4jConfig.properties</param-value>
    </context-param>
    <context-param>
        <param-name>ignoreKeys</param-name>
        <param-value>catalina.home</param-value>
    </context-param>
    <context-param>
        <param-name>cautious_enable</param-name>
        <param-value>true</param-value>
    </context-param>
    <context-param>
        <param-name>disconf_appName</param-name>
        <param-value>aaaName</param-value>
    </context-param>
    <context-param>
        <param-name>disconf_appFileNames</param-name>
        <param-value>filter-docker-spare.properties</param-value>
    </context-param>
    <context-param>
        <param-name>disconf_appEnv</param-name>
        <param-value>dev</param-value>
    </context-param>
    <context-param>
        <param-name>disconf_appVersion</param-name>
        <param-value>1.0.0</param-value>
    </context-param>

- **enable_remote**: 远程或本地模式
- **repositoryURL**: 远程模式下为远程api地址，本地模式下为配置文件路径
- **ignoreFiles**: 校验时需要忽略的文件
- **ignoreKeys**: 替换时需要忽略的项
- **cautious_enable**: 谨慎的模式

- **disconf_appName**: 项目名
- **disconf_appFileNames**: 托管的配置文件名
- **disconf_appEnv**: 环境
- **disconf_appVersion**: 版本


#### 逻辑原理

1. 访问配置中心(远程or本地)的配置，如果获得不了配置，或者没有配置项，将短路整个过程。

2. 筛选可能配置文件(WEB-INF该目录下子目录)。

	- 对于classes目录，我们仅筛选**该目录**下的.properties文件不包括其子目录

	- **其他目录**则是完全递归筛选(properties和xml)
	
	- **没有处理WEB-INF下的文件**

3. 筛选的配置文件备份
    - **WEB-INF下deploy_tmp**

4. 处理备份的配置文件，并将处理后的内容，写回源文件中，完成占位符替换。

5. 程序正常启动


#### 策略 ####

我们总是会检验当前的配置文件是否含有占位符。

我们提供了一种保守策略。

1. 所有校验的配置文件都不含占位符，保守策略不开启的情况下，我们将短路整个过程。
2. 保守策略开启的情况下，我们将总是从最近一次的备份推导回写实际的配置。如果备份不存在，我们将进行回退到正常模式。
3. 正常模式下，我们将受扫描的配置文件备份，并对备份处理后进行回写。

> tip: 禁用保守策略下,你备份通常会在你出错的时候进行轮转。即你不能依赖备份的校验来告诉你，你那里出错了。
> 
> 禁用保守策略,也就是配置的错误情况下，请重新打包部署，而不是简单重启。


#### FAQ

1. 筛选配置文件的时候，可以通过`ignoreFiles`进行忽略.
    - 比如log4j的某些占位符不影响分析
1. 处理配置文件的时候，发现不能解析的**${}**，将会出错。 可以通过`ignoreKeys`进行忽略
    - 比如log4j的某些占位符不影响分析
2. 仅仅支持将配置文件托管给disconf管理，不支持动态加载
3. 本地模式,你需要指定你所用的properties文件的文件名，或者所在目录的路径。
4. 我们不区分注解下的**${}**符号，如果一段配置没有用，请直接删掉它

#### 最佳实践

- 修改disconf配置后，重新部署。使用CI,CD流程,避免在机器上直接操作


#### maven结合的最佳实践 ####

我们有着不同的环境，即使有着远程的配置，但是我们需要构建不同的信息去访问远程的配置


如果你使用war形式的。

	  <resources>
            <resource>
                <directory>
                    src/main/resources
                </directory>
            </resource>
            <resource>
                <directory>
                    src/main/webapp/WEB-INF
                </directory>
                <filtering>true</filtering>
                <includes>
                    <include>web.xml</include>
                </includes>
                <targetPath>${project.build.directory}/${project.build.finalName}/WEB-INF</targetPath>
            </resource>
       </resources>

我们的客户端需要处理的web.xml。 因此需要多配一个处理，

			<plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-war-plugin</artifactId>
                <version>2.1.1</version>
                <configuration>
                    <warName>nbgl2-wlgl</warName>
                    <warSourceExcludes>WEB-INF/web.xml</warSourceExcludes>
                    <outputDirectory>${project.build.directory}/dist</outputDirectory>
                </configuration>
            </plugin>

我们在resource中已经处理过了，因此war包的时候，请将它排除。避免复制时无效




	

