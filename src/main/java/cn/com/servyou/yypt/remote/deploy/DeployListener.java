package cn.com.servyou.yypt.remote.deploy;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.PropertyPlaceholderHelper;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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

    private final PlaceholderResolvingResolver placeholderResolvingResolver = new PlaceholderResolvingResolver();

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        String enable = servletContextEvent.getServletContext().getInitParameter(ENABLE_REMOTE);
        if (!"true".equalsIgnoreCase(enable)){
            logger.info("use local mode.");
            return;
        }
        logger.info("use remote mode");
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
        Properties properties = fetchDeployMeta(remoteUrl);
        if (properties.isEmpty()) {
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

    private Properties fetchDeployMeta(String urlStr) {
        try {
            URL url = new URL(urlStr);
            File file = new File(System.getProperty("user.dir") + File.separator + "tmp");
            if (!file.exists()) {
                file.mkdir();
            }
            File tmpFile = new File(file, "dconf.properties");
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            FileUtils.copyURLToFile(url, tmpFile);
            Properties properties = new Properties();
            properties.load(new FileReader(tmpFile));
            return properties;
        } catch (MalformedURLException e) {
            logger.error("can't transform to url:{}", urlStr);
            throw new RuntimeException("error config " + urlStr);
        } catch (IOException e) {
            logger.warn("can't download remote file:{}", urlStr, e);
        }
        return new Properties();
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
                if (currentFileName.contains(".properties") && currentFileName.contains(".xml")) {
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
                    placeholderPrefix, placeholderSuffix, valueSeparator, false);
        }

        String resolveStringValue(String strValue, PropertyPlaceholderHelper.PlaceholderResolver resolver) {
            return this.helper.replacePlaceholders(strValue, resolver);
        }
    }

    /**
     * from spring
     */
    private static class RemotePropertyPlaceholderResolver implements PropertyPlaceholderHelper.PlaceholderResolver {

        private final String fileName;

        private final Properties props;

        RemotePropertyPlaceholderResolver(String fileName, Properties props) {
            this.fileName = fileName;
            this.props = props;
        }

        @Override
        public String resolvePlaceholder(String placeholderName) {
            try {
                String propVal = (String) props.get(placeholderName);
                if (propVal == null) {
                    logger.error("can't find placeholder:{} 's value in [{}]", placeholderName, fileName);
                }
                return propVal;
            } catch (Throwable ex) {
                logger.error("can't resolve placeholder :{} in [{}]", placeholderName, fileName, ex);
                return null;
            }
        }
    }

}
