package cn.com.servyou.yypt.remote.deploy;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.PropertyPlaceholderHelper;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import static org.springframework.beans.factory.config.PlaceholderConfigurerSupport.DEFAULT_PLACEHOLDER_PREFIX;
import static org.springframework.beans.factory.config.PlaceholderConfigurerSupport.DEFAULT_PLACEHOLDER_SUFFIX;
import static org.springframework.beans.factory.config.PlaceholderConfigurerSupport.DEFAULT_VALUE_SEPARATOR;

/**
 * <p>Description: </p>
 * <p>税友软件集团有限公司</p>
 *
 * @author laihj
 * 2018/11/27
 */
public class DeployListener implements ServletContextListener {

    private static final Logger logger = LoggerFactory.getLogger(DeployListener.class);

    private static final String DEPLOY_REPOSITORY_URL = "repositoryURL";

    private static final String DEPLOY_TMP = "deploy_tmp";

    private static final String TAG = "${";

    private static final String ENABLE_REMOTE = "enable_remote";

    private static final String IGNORE_VALUE = "ignoreKeys";

    private static final String IGNORE_FILE_VALUE = "ignoreFiles";

    private static final String APP = "iris_appName";

    private static final String KEY = "iris_appFileNames";

    private static final String ENV = "iris_appEnv";

    private static final String VERSION = "iris_appVersion";

    @Deprecated
    private final Set<String> ignores = new HashSet<String>(64);
    private final Set<String> ignoreFiles = new HashSet<String>(64);

    private final PlaceholderResolvingResolver placeholderResolvingResolver = new PlaceholderResolvingResolver();

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        try {
            //检查模式
            String enable = servletContextEvent.getServletContext().getInitParameter(ENABLE_REMOTE);
            if (!"true".equalsIgnoreCase(enable)) {
                logger.info("use local mode.");
                return;
            }
            logger.info("use remote mode");

            //解析忽略的文件配置，用于校验本次是否需要进行替代
            String ignoreFiles = System.getProperty(IGNORE_VALUE);
            if (ignoreFiles == null || "".equals(ignoreFiles.trim())) {
                ignoreFiles = servletContextEvent.getServletContext().getInitParameter(IGNORE_FILE_VALUE);
            }
            if (ignoreFiles != null && !"".equals(ignoreFiles)) {
                String[] fileKeys = ignoreFiles.split(",");
                for (String file : fileKeys) {
                    this.ignoreFiles.add(file);
                }
            }

            //解析忽略的key
            String ignoreKey = System.getProperty(IGNORE_VALUE);
            if (ignoreKey == null || "".equals(ignoreKey.trim())) {
                ignoreKey = servletContextEvent.getServletContext().getInitParameter(IGNORE_VALUE);
            }
            if (ignoreKey != null && !"".equals(ignoreKey)) {
                String[] keys = ignoreKey.split(",");
                for (String key : keys) {
                    ignores.add(key);
                }
            }

            //构建irisMeta或disconf meta
            //todo ignore which remote technology we use
            List<IrisMeta> irisMetas = buildMetas(servletContextEvent.getServletContext());


            String rootPath = servletContextEvent.getServletContext().getRealPath("/") + "WEB-INF";
            if (rootPath == null) {
                return;
            }
            File rootFile = new File(rootPath);
            if (!rootFile.exists()) {
                logger.info("file:{} didn't exists", rootFile);
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
            String remoteUrl = System.getProperty(DEPLOY_REPOSITORY_URL);
            if (remoteUrl == null || "".equals(remoteUrl.trim())) {
                remoteUrl = servletContextEvent.getServletContext().getInitParameter(DEPLOY_REPOSITORY_URL);
            }
            if (remoteUrl == null || "".equals(remoteUrl.trim())) {
                return;
            }
            Properties properties = fetchDeployMeta(remoteUrl, irisMetas);
            if (properties.isEmpty()) {
                logger.warn("config propeties is empty. has any problem? anything will not be replacement");
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
            if (checkClasses(classesDir) && checkSubDirs(subDirs)) {
                logger.info("no thing need to be resolve.");
                return;
            }
            final String deployTmpPath = rootPath + File.separator + DEPLOY_TMP;
            try {
                FileUtils.deleteDirectory(new File(deployTmpPath));
            } catch (IOException e) {
                logger.error("delete file failed", e);
            }

            try {
                FileUtils.forceMkdir(new File(deployTmpPath));
            } catch (IOException e) {
                logger.error("mkdir failed", e);
            }

            try {
                for (File subDir : subDirs) {
                    FileUtils.copyDirectory(subDir, new File(deployTmpPath + File.separator + subDir.getName()));
                }
            } catch (IOException e) {
                logger.error("copy dir failed", e);
            }

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

            dealAllFile(new File(deployTmpPath), properties);
        } catch (Exception e) {
            logger.error("can't work fine because:{}", e);
        }
    }

    private List<IrisMeta> buildMetas(ServletContext servletContext) {
        String app = servletContext.getInitParameter(APP);
        String env = servletContext.getInitParameter(ENV);
        String version = servletContext.getInitParameter(VERSION);
        String keys = servletContext.getInitParameter(KEY);
        if (app == null || "".equals(app)) {
            throw new IllegalStateException("illegal app can't be null");
        }
        if (env == null || "".equals(env)) {
            throw new IllegalStateException("illegal env can't be null");
        }
        if (version == null || "".equals(version)) {
            throw new IllegalStateException("illegal version can't be null");
        }
        if (keys == null || "".equals(keys)) {
            throw new IllegalStateException("illegal keys can't be null");
        }
        String[] key = keys.split(",");
        List<IrisMeta> irisMetas = new ArrayList<IrisMeta>();
        for (String singleKey : key) {
            irisMetas.add(new IrisMeta(app, version, singleKey, env));
        }
        return irisMetas;
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

    private Properties fetchDeployMeta(String urlStr, List<IrisMeta> irisMetas) {
        Properties properties = new Properties();
        if (irisMetas == null || irisMetas.size() == 0) {
            return properties;
        }
        for (IrisMeta irisMeta : irisMetas) {
            Properties singlePro = buildSinglePro(urlStr, irisMeta);
            for (String key : singlePro.stringPropertyNames()) {
                properties.put(key, singlePro.get(key));
            }
        }
        return properties;
    }

    private Properties buildSinglePro(String urlStr, IrisMeta irisMeta) {
        Properties properties = new Properties();
        if (irisMeta == null) {
            return properties;
        }
        try {
            URL url = new URL(urlStr + irisMeta.toUrlParameters());
            StringBuilder backUp = new StringBuilder(System.getProperty("user.dir"));
            backUp.append(File.separator)
                    .append("tmp")
                    .append(File.separator)
                    .append(irisMeta.env)
                    .append(File.separator)
                    .append(irisMeta.app)
                    .append(File.separator)
                    .append(irisMeta.version);
            File file = new File(backUp.toString());
            if (!file.exists()) {
                file.mkdirs();
            }
            File tmpFile = new File(file, irisMeta.key);
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            FileUtils.copyURLToFile(url, tmpFile);
            properties.load(new FileReader(tmpFile));
            String content = FileUtils.readFileToString(tmpFile);
            logger.info("config show start ---------------------------------------------------------");
            logger.info("\n{}", content);
            logger.info("config end  start ---------------------------------------------------------");
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

    private boolean checkFile(File file) {
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

        private final String fileName;

        private final Properties props;

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
                    logger.info("ignore un resolve key's value in [{}]", placeholderName, fileName);
                    return null;
                }
            }
            return propVal;
        }
    }

    public static void main(String[] args) {
        Map<String, String> parameter = new HashMap<String, String>();
        parameter.put("version", "1.0.0");
        parameter.put("app", "nbgl_wlgl");
        parameter.put("env", "dev");
        parameter.put("key", "filter-docker-spare.properties");
        parameter.put("type", "0");
        StringBuilder builder = new StringBuilder("http://192.168.150.165:9002/servyconf/api/config/file");
        builder.append("?");
        for (String thisKey : parameter.keySet()) {
            String cur = thisKey + "=" + parameter.get(thisKey);
            cur += "&";
            builder.append(cur);
        }
        if (builder.length() > 0) {
            builder.deleteCharAt(builder.length() - 1);
        }
        try {
            URL url = new URL(builder.toString());
            System.out.println(url);
            File file = new File(System.getProperty("user.dir") + File.separator + "tmp");
            if (!file.exists()) {
                file.mkdir();
            }
            File tmpFile = new File(file, "dconf.properties");
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            FileUtils.copyURLToFile(url, tmpFile);
            String readline;
            BufferedReader reader = new BufferedReader(new FileReader(tmpFile));
            while ((readline = reader.readLine()) != null) System.out.println(readline);
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    private class IrisMeta {
        private String app;
        private String version;
        private String key;
        private String env;

        public IrisMeta(String app, String version, String key, String env) {
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
