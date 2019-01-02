package cn.com.servyou.yypt.remote.deploy.fetch;

import java.util.Map;

/**
 * <p>Description: </p>
 * <p>税友软件集团有限公司</p>
 *
 * @author laihj
 * 2019/1/2
 */
public interface Fetcher {

    Map<String, Object> fetcherMeta(String url);

}
