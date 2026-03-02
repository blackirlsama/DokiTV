package com.orangecode.tianmu.utils;

public class ContentTypeUtil {

    /**
     * @MethodName getType
     * @Description 获取文件类型
     * @param: fileSuffix
     * @return: String
     * @Date 2025/4/10 15:30
     */
    public String getType(String fileSuffix) {
        String contentType = "application/octet-stream";  // 默认二进制文件类型
        switch (fileSuffix) {
            case "jpg":
            case "jpeg":
                contentType = "image/jpeg";
                break;
            case "png":
                contentType = "image/png";
                break;
            case "gif":
                contentType = "image/gif";
                break;
            case "mp4":
                contentType = "video/mp4";
                break;
            case "mov":
                contentType = "video/quicktime";
                break;
            case "avi":
                contentType = "video/x-msvideo";
                break;
            case "webm":
                contentType = "video/webm";
                break;
            // 你可以根据实际需要继续添加其他图片格式
            default:
                contentType = "application/octet-stream"; // 默认的二进制流
                break;
        }
        return contentType;
    }
}
