package cn.com.servyou.yypt.remote.deploy.fetch;

import java.util.Map;

/**
 * <p>Description:  only for adaptor so only coding in one listener is ok</p>
 * <p>税友软件集团有限公司</p>
 *
 * @author laihj
 * 2019/1/2
 */
@Deprecated
public interface Fetcher {

    Map<String, Object> fetcherMeta(String url);

}
