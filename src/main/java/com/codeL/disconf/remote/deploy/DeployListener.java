package com.codeL.disconf.remote.deploy;

import com.codeL.disconf.remote.deploy.util.PropertyPlaceholderHelper;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * <p>
 * Description: this is only for adapting 。 so we didn't split to classes.
 * </p>
 * <p>
 * We gather responsibility in one file。 because this is not more complex
 * </p>
 *
 * <p>
 * More info see readme
 * </p>
 *
 * @author laihj
 * 2018/11/27
 */
public class DeployListener implements ServletContextListener {

    public static final String DEFAULT_PLACEHOLDER_PREFIX = "${";
    public static final String DEFAULT_PLACEHOLDER_SUFFIX = "}";
    public static final String DEFAULT_VALUE_SEPARATOR = ":";


    private static final Logger logger = LoggerFactory.getLogger(DeployListener.class);

    /**
     * 资源后缀
     */
    private static final String URL = "/api/config/file";

    /**
     * 后备目录
     */
    private static final String DEPLOY_TMP = "deploy_tmp";

    /**
     * 占位符
     */
    private static final String TAG = "${";

    /**
     * 远程的仓库的url
     */
    private static final String DEPLOY_REPOSITORY_URL = "repositoryURL";

    /**
     * 开关用于开启本地和远程模式
     */
    private static final String ENABLE_REMOTE = "enable_remote";

    /**
     * 在解析过程需要忽略掉不会被解析的${}
     *
     * <p>
     * ex: catalina.home
     * </p>
     */
    private static final String IGNORE_VALUE = "ignoreKeys";

    /**
     * 在验证过程中需要忽略掉不会引发校验的文件
     */
    private static final String IGNORE_FILE_VALUE = "ignoreFiles";

    /**
     * 保守的策略
     */
    private static final String CAUTIOUS_ENABLE = "cautious_enable";

    /**
     * 名字
     */
    private static final String APP = "disconf_appName";

    /**
     * 文件名
     */
    private static final String KEY = "disconf_appFileNames";

    /**
     * 环境
     */
    private static final String ENV = "disconf_appEnv";

    /**
     * 版本
     */
    private static final String VERSION = "disconf_appVersion";

    /**
     * 替换时忽略的项
     */
    @Deprecated
    private final Set<String> ignores = new HashSet<String>(64);

    /**
     * 检查时忽略的文件
     */
    private final Set<String> ignoreFiles = new HashSet<String>(64);


    /**
     * 使用过的配置项
     */
    private final Set<String> usedConf = new HashSet<String>(128);

    /**
     * 解析器
     */
    private final PlaceholderResolvingResolver placeholderResolvingResolver = new PlaceholderResolvingResolver();

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {

        ServletContext context = servletContextEvent.getServletContext();

        //检查模式
        boolean useLocal = false;
        String enable = parseAndGetSingleValue(context, ENABLE_REMOTE);
        if (!"true".equalsIgnoreCase(enable)) {
            logger.info("use local mode.");
            useLocal = true;
        } else {
            logger.info("use remote mode");
        }
        //解析忽略的文件配置，用于校验本次是否需要进行替代
        Set<String> parsedIgnoreFiles = parseAndGetValues(context, IGNORE_FILE_VALUE, ",");
        if (parsedIgnoreFiles != null) {
            this.ignoreFiles.addAll(parsedIgnoreFiles);
        }

        //解析忽略的key
        Set<String> parsedIgnoreKeys = parseAndGetValues(context, IGNORE_VALUE, ",");
        if (parsedIgnoreKeys != null) {
            this.ignores.addAll(parsedIgnoreKeys);
        }

        // 构建disconfMeta或disconf meta
        // deprecated plan that: todo ignore which remote technology we use
        List<DisconfMeta> disconfMetas = buildMetas(context);

        String root = context.getRealPath("/");
        if (!root.endsWith(File.separator)) {
            root = root + File.separator;
        }
        String rootPath = root + "WEB-INF";
        File rootFile = new File(rootPath);
        if (!rootFile.exists()) {
            logger.warn("file:{} didn't exists", rootFile);
            return;
        }

        //查找classes目录
        String classesPath = rootPath + File.separator + "classes";
        File classesDir = new File(classesPath);
        if (!classesDir.exists()) {
            logger.info("file:{} didn't exists", classesDir);
            return;
        }

        //1. 访问远程的仓库，获得相关配置文件项
        String url = parseAndGetSingleValue(context, DEPLOY_REPOSITORY_URL);
        if (url == null || "".equals(url)) {
            throw new IllegalStateException("repositoryURL config not found");
        }

        //处理两种模式
        Properties properties = useLocal ? fetchDeploy(url) : fetchDeployMeta(url + URL, disconfMetas);
        if (properties.isEmpty()) {
            logger.warn("config properties is empty. has any problem? anything will not be replacement");
            return; //认为我们已经无能为力了，发生重启时，但是配置文件已经被替换好了
        }

        //构建可能存在的配置文件的目录
        List<File> subDirs = new ArrayList<File>();
        for (File subFile : rootFile.listFiles()) {
            if (!subFile.isDirectory()) {
                continue;
            }
            if ("lib".equals(subFile.getName())) {
                continue;
            }
            if ("classes".equals(subFile.getName())) {
                continue;
            }
            if (DEPLOY_TMP.equals(subFile.getName())) {
                continue;
            }
            subDirs.add(subFile);
        }

        //检查保守策略
        boolean cautiousEnable = false;
        String cautiousEnableStr = parseAndGetSingleValue(context, CAUTIOUS_ENABLE);
        if ("true".equalsIgnoreCase(cautiousEnableStr)) {
            cautiousEnable = true;
        }

        //检查是否需要进行相关解析。
        boolean passCheck = checkClasses(classesDir) && checkSubDirs(subDirs);
        if (passCheck && !cautiousEnable) {
            logger.info("nothing needs to be resolve. use uncautious strategy");
            return;
        }
        //目标的目录
        final String deployTmpPath = rootPath + File.separator + DEPLOY_TMP;

        //保守策略下的处理
        if (passCheck) {
            logger.info("use cautious strategy");
            File deployTemp = new File(deployTmpPath);
            if (deployTemp.exists()) {
                dealAllFile(new File(deployTmpPath), properties);
                reportResult(properties);
                return;
            }
        }

        //正常策略下的处理，ignore 是否passcheck成功。
        // 1. 删除目标
        try {
            FileUtils.deleteDirectory(new File(deployTmpPath));
        } catch (IOException e) {
            logger.error("delete file failed", e);
        }

        // 2. 构建目标
        try {
            FileUtils.forceMkdir(new File(deployTmpPath));
        } catch (IOException e) {
            logger.error("mkdir failed", e);
        }

        // 3. 拷贝子目录
        try {
            for (File subDir : subDirs) {
                FileUtils.copyDirectory(subDir, new File(deployTmpPath + File.separator + subDir.getName()));
            }
        } catch (IOException e) {
            logger.error("copy dir failed", e);
        }

        // 4. 拷贝class目录东西
        try {
            File[] configProps = classesDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.contains(".properties");
                }
            });
            if (configProps != null) {
                File deployClassesDir = new File(deployTmpPath + File.separator + classesDir.getName());
                deployClassesDir.mkdir();
                for (File file : configProps) {
                    FileUtils.copyFileToDirectory(file, deployClassesDir);
                }
            }
        } catch (IOException e) {
            logger.error("copy file failed", e);
        }
        // 5. 处理
        dealAllFile(new File(deployTmpPath), properties);

        // 6. report
        reportResult(properties);
    }

    /**
     * for local
     *
     * @param localUrl
     * @return
     */
    private Properties fetchDeploy(String localUrl) {
        Properties properties = new Properties();
        File file = new File(localUrl);
        if (!file.exists()) {
            logger.error("error unknown for you file:[{}]. it didn't exists. local mode error", localUrl);
            throw new IllegalStateException("error state, error unknown for you file. it didn't exists. local mode error");
        }
        if (file.isFile() && file.getName().endsWith(".properties")) {
            logger.info("user local config file:[{}]", localUrl);
            try {
                properties.load(new FileReader(file));
            } catch (IOException e) {
                logger.error("read file happen error. please chek you file:[{}]", file.getName(), e);
                throw new IllegalStateException("error state, can't read file:" + file.getName() + e.getMessage());
            }
        }
        if (file.isDirectory()) {
            File[] proFiles = file.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".properties");
                }
            });
            if (proFiles != null && proFiles.length != 0) {
                logger.info("user local config dir:[{}]", localUrl);
                for (File proFile : proFiles) {
                    try {
                        properties.load(new FileReader(proFile));
                    } catch (IOException e) {
                        logger.error("read file happen error. please chek you file:{}", file.getName(), e);
                        throw new IllegalStateException("error state, can't read file:" + file.getName() + e.getMessage());
                    }
                }
            }
        }
        return properties;
    }

    /**
     * 报告结果 主要是报告未使用的key
     *
     * @param properties 配置key
     */
    private void reportResult(Properties properties) {
        if (usedConf.size() == 0) {
            return;
        }
        Set<Object> copy = new HashSet<Object>(properties.keySet());
        copy.removeAll(usedConf);
        if (copy.size() == 0) {
            return;
        }
        /**
         * we didn't use String builder to contact . for simple view
         */
        for (Object key : copy) {
            logger.warn("key:[{}] un used. please check you code!!!!!", key);
        }
    }

    /**
     * 解析获得当个值
     *
     * @param servletContext 上下文
     * @param configKey      键
     * @return string单值
     */
    private String parseAndGetSingleValue(ServletContext servletContext, String configKey) {
        if (configKey == null || "".equals(configKey.trim())) {
            throw new IllegalArgumentException(" configKey can't be null or \"\"");
        }
        String value = System.getProperty(configKey);
        if (value == null || "".equals(value.trim())) {
            value = servletContext.getInitParameter(configKey);
        }
        return value;
    }

    /**
     * 解析获得多个值
     *
     * @param servletContext 上下文
     * @param configKey      键
     * @param splitter       分隔符
     * @return set多个值
     */
    private Set<String> parseAndGetValues(ServletContext servletContext, String configKey, String splitter) {
        String values = parseAndGetSingleValue(servletContext, configKey);
        if (values == null || "".equals(values)) {
            return null;
        }
        if (splitter == null) {
            splitter = ",";
        }
        String[] keys = values.split(splitter);
        Set<String> keySet = new HashSet<String>();
        for (String key : keys) {
            keySet.add(key);
        }
        return keySet;
    }

    /**
     * 构建多个元信息
     *
     * @param servletContext
     * @return disconf配置
     */
    private List<DisconfMeta> buildMetas(ServletContext servletContext) {
        String app = parseAndGetSingleValue(servletContext, APP);
        String env = parseAndGetSingleValue(servletContext, ENV);
        String ver = parseAndGetSingleValue(servletContext, VERSION);
        String keys = parseAndGetSingleValue(servletContext, KEY);
        if (keys == null || "".equals(keys)) {
            throw new IllegalStateException("illegal key can't be null");
        }
        String[] key = keys.split(",");
        List<DisconfMeta> disconfMetas = new ArrayList<DisconfMeta>();
        for (String singleKey : key) {
            disconfMetas.add(new DisconfMeta(app, ver, singleKey, env));
        }
        return disconfMetas;
    }

    /**
     * 处理所有文件
     *
     * @param file
     * @param properties
     */
    private void dealAllFile(File file, Properties properties) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null) {
                return;
            }
            for (File subFile : files) {
                dealAllFile(subFile, properties);
            }
            return;
        }
        try {
            String content = FileUtils.readFileToString(file);
            String goalContent = this.placeholderResolvingResolver.resolveStringValue(content, new RemotePropertyPlaceholderResolver(content, properties));
            String path = file.getAbsolutePath();
            path = path.replaceAll(DEPLOY_TMP, ""); //这是可以的
            File goalFile = new File(path);
            if (!goalFile.exists()) {
                logger.error("file :{} not exists", path);
                return;
            }
            FileUtils.writeByteArrayToFile(goalFile, goalContent.getBytes());
        } catch (IOException e) {
            logger.error("copy to goal path happend error file:{}", file.getAbsolutePath(), e);
        }
    }

    /**
     * for remote
     *
     * @param urlStr
     * @param disconfMetas
     * @return
     */
    private Properties fetchDeployMeta(String urlStr, List<DisconfMeta> disconfMetas) {
        Properties properties = new Properties();
        if (disconfMetas == null || disconfMetas.size() == 0) {
            return properties;
        }
        for (DisconfMeta disconfMeta : disconfMetas) {
            Properties singlePro = buildSinglePro(urlStr, disconfMeta);
            for (String key : singlePro.stringPropertyNames()) {
                if (properties.contains(key)) {
                    logger.warn("has some key [{}] in confile!!!!!", key);
                }
                properties.put(key, singlePro.get(key));
            }
        }
        return properties;
    }

    private Properties buildSinglePro(String urlStr, DisconfMeta disconfMeta) {
        Properties properties = new Properties();
        if (disconfMeta == null) {
            return properties;
        }
        try {
            URL url = new URL(urlStr + disconfMeta.toUrlParameters());
            StringBuilder backUp = new StringBuilder(System.getProperty("user.dir"));
            backUp.append(File.separator)
                    .append("tmp")
                    .append(File.separator)
                    .append(disconfMeta.env)
                    .append(File.separator)
                    .append(disconfMeta.app)
                    .append(File.separator)
                    .append(disconfMeta.version);
            File file = new File(backUp.toString());
            if (!file.exists()) {
                file.mkdirs();
            }
            File tmpFile = new File(file, disconfMeta.key);
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            FileUtils.copyURLToFile(url, tmpFile);
            properties.load(new FileReader(tmpFile));
            String content = FileUtils.readFileToString(tmpFile);
            logger.info("config show start ---app:{}---env:{}---version:{}---configFile:{}", disconfMeta.app, disconfMeta.env, disconfMeta.version, disconfMeta.key);
            logger.info("\n{}", content);
            logger.info("config end  start ---app:{}---env:{}---version:{}---configFile:{}", disconfMeta.app, disconfMeta.env, disconfMeta.version, disconfMeta.key);
            return properties;
        } catch (MalformedURLException e) {
            logger.error("can't transform to url:{}", urlStr);
            throw new RuntimeException("error config " + urlStr);
        } catch (IOException e) {
            logger.warn("can't download remote file:{}", urlStr, e);
        }
        return properties;
    }

    /**
     * 我们仅仅处理一级，并且是properties文件
     *
     * @param classesDir
     * @return
     */
    private boolean checkClasses(File classesDir) {
        File[] propFiles = classesDir.listFiles();
        if (propFiles == null) {
            return true;
        }
        String currentFileName = null;
        String lineContent = null;
        int lineNumber = 0;
        try {
            for (File prop : propFiles) {
                if (!prop.getName().contains(".properties")) {
                    continue;
                }
                if (ignoreFiles.contains(prop.getName())) {
                    continue;
                }
                currentFileName = prop.getName();
                BufferedReader reader = new BufferedReader(new FileReader(prop));
                while ((lineContent = reader.readLine()) != null) {
                    lineNumber += 1;
                    if (!lineContent.contains(TAG)) {
                        continue;
                    }
                    reader.close();
                    return false;
                }
            }
        } catch (Exception e) {
            logger.error("read file happend error filename:{},line:{},content:{}", currentFileName, lineNumber, lineContent, e);
        }
        return true;
    }

    /**
     * @param subDirs
     * @return
     */
    private boolean checkSubDirs(List<File> subDirs) {
        for (File subDir : subDirs) {
            if (!doCheckDir(subDir)) {
                return false;
            }
        }
        return true;
    }

    /**
     * for check file
     *
     * @param file
     * @return
     */
    protected boolean checkFile(File file) {
        return true;
    }

    /**
     * /**
     *
     * @param subDir
     */
    private boolean doCheckDir(File subDir) {
        File[] files = subDir.listFiles();
        if (files == null) {
            return true;
        }
        String currentFileName = null;
        String readline = null;
        int lineNumber = 0;
        try {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (!doCheckDir(file)) {
                        return false;
                    }
                }
                currentFileName = file.getName();
                if (ignoreFiles.contains(currentFileName)) {
                    continue;
                }
                if (currentFileName.contains(".properties") || currentFileName.contains(".xml")) {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    while ((readline = reader.readLine()) != null) {
                        lineNumber += 1;
                        if (!readline.contains(TAG)) {
                            continue;
                        }
                        reader.close();
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("read file happend error filename:{},line:{},content:{}", currentFileName, lineNumber, readline, e);
        }
        return true;
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        //ignore
    }

    Set<String> ignores() {
        return Collections.synchronizedSet(ignores);
    }

    /**
     * from spring
     */
    private static class PlaceholderResolvingResolver {

        private final String placeholderPrefix = DEFAULT_PLACEHOLDER_PREFIX;

        private final String placeholderSuffix = DEFAULT_PLACEHOLDER_SUFFIX;

        private final String valueSeparator = DEFAULT_VALUE_SEPARATOR;

        private final PropertyPlaceholderHelper helper;

        PlaceholderResolvingResolver() {
            this.helper = new PropertyPlaceholderHelper(
                    placeholderPrefix, placeholderSuffix, valueSeparator, true);
        }

        String resolveStringValue(String strValue, PropertyPlaceholderHelper.PlaceholderResolver resolver) {
            return this.helper.replacePlaceholders(strValue, resolver);
        }
    }

    /**
     * from spring
     */
    private class RemotePropertyPlaceholderResolver implements PropertyPlaceholderHelper.PlaceholderResolver {

        /**
         * 当前的文件名
         */
        private final String fileName;

        /**
         * 数据项
         */
        private final Properties props;

        /**
         * 可以被忽略的数据项
         */
        private final Set<String> ignores;

        RemotePropertyPlaceholderResolver(String fileName, Properties props) {
            this.fileName = fileName;
            this.props = props;
            this.ignores = ignores();
        }

        @Override
        public String resolvePlaceholder(String placeholderName) {
            String propVal = (String) props.get(placeholderName);
            if (propVal == null) {
                if (!ignores.contains(placeholderName)) {
                    logger.error("can't find placeholder:{} 's value in [{}]", placeholderName, fileName);
                    throw new IllegalStateException("can't resolve placeholder:[" + placeholderName + "] in [" + fileName + "]");
                } else {
                    logger.info("ignore unresolved key:[{}] in [{}]", placeholderName, fileName);
                    return null;
                }
            }
            usedConf.add(placeholderName);
            return propVal;
        }
    }

    /**
     * disconf对应的元信息
     */
    private class DisconfMeta {

        /**
         * app名称
         */
        private String app;
        /**
         * 版本
         */
        private String version;
        /**
         * key通常是文件名
         */
        private String key;
        /**
         * 环境
         */
        private String env;

        DisconfMeta(String app, String version, String key, String env) {
            if (app == null || "".equals(app)) {
                throw new IllegalStateException("illegal app can't be null");
            }
            if (env == null || "".equals(env)) {
                throw new IllegalStateException("illegal env can't be null");
            }
            if (version == null || "".equals(version)) {
                throw new IllegalStateException("illegal version can't be null");
            }
            if (key == null || "".equals(key)) {
                throw new IllegalStateException("illegal key can't be null");
            }
            this.app = app;
            this.version = version;
            this.key = key;
            this.env = env;
        }

        public String getApp() {
            return app;
        }

        public void setApp(String app) {
            this.app = app;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getEnv() {
            return env;
        }

        public void setEnv(String env) {
            this.env = env;
        }

        /**
         * build down load 打参数
         *
         * @return
         */
        String toUrlParameters() {
            StringBuilder builder = new StringBuilder("?");
            return builder.append("app=")
                    .append(app)
                    .append("&env=")
                    .append(env)
                    .append("&key=")
                    .append(key)
                    .append("&version=")
                    .append(version)
                    .toString();
        }
    }

}
