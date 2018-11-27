## 项目差异性解决

### 背景 ###

由于历史遗留问题，很多项目没有使用远程配置中心(disconf)。比如商务，客服等等，maven打包后将完成项目配置替换。主要将${}替换为真实值。

### 问题 ###

项目中filter过多，维护不方便。同时为了部分的安全，线上配置很多需要配置到线上对应的mavan的setting中。

![](http://10.209.130.126:8001/statics/servyou/img/1.png)

### simpile解决方案 ###

simpile解决方案为：
	
	配置文件托管给disconf

优点：

所有配置全由disconf托管

缺点：

1. 不支持disconf动态修改
2. 修改disconf配置，必须重新部署而不是简单重启

也就说我们仅仅支持将文件托管给disconf管理。

这是由于，只是兼容历史遗留项目，新项目则更应该使用配置中心机制。


#### 如何配置 ####

pom

        <dependency>
		    <groupId>cn.com.servyou.yypt</groupId>
		    <artifactId>simple-remoteConf-client</artifactId>
			<version>0.0.1-SNAPSHOT</verison>
        </dependency>


web.xml 

	<listener>
        <listener-class>
            cn.com.servyou.yypt.remote.deploy.DeployListener
        </listener-class>
    </listener>
    <context-param>
        <param-name>repositoryURL</param-name>
        <param-value>http://localhost:8001/xxxx/filter-develop.properties</param-value>
    </context-param>

repositoryURL值为下载地址


#### 逻辑原理

1. 尝试访问配置中心的配置，如果获得不了配置，将不生效
2. 筛选可能配置文件，比如
	![](http://10.209.130.126:8001/statics/servyou/img/2.png)

	- 对于classes目录，我们进筛选其子一级目录下的.properties文件
		![](http://10.209.130.126:8001/statics/servyou/img/3.png)

	- 其他目录则是完全递归筛选(properties和xml)

    > **tip**:如果所有配置文件中全无（**${}**），我们将跳过配置，因为我们假设这是一次紧急重启。如果不是紧急重启，建议使用走部署应用的流程。
		
3. 将筛选的配置文件放置一个临时目录中
	![](http://10.209.130.126:8001/statics/servyou/img/4.png)
    > **tip**：在放置前，我们将清空临时目录。

4. 处理临时目录中的配置文件，并将处理后的内容，写回源文件中，完成占位符替换。
5. 程序正常启动



#### FAQ

1. 运行时，处理配置文件，发现不能解析的**${}**，将会出错
2. 仅仅支持将配置文件托管给disconf管理，不支持动态加载

#### 最佳实践

- 修改disconf配置后，重新部署。 避免在机器上直接操作




	

