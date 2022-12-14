package net.lab1024.sa.common.module.support.file.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import net.lab1024.sa.common.common.code.SystemErrorCode;
import net.lab1024.sa.common.common.code.UserErrorCode;
import net.lab1024.sa.common.common.constant.StringConst;
import net.lab1024.sa.common.common.domain.PageResult;
import net.lab1024.sa.common.common.domain.RequestUser;
import net.lab1024.sa.common.common.domain.ResponseDTO;
import net.lab1024.sa.common.common.util.SmartBeanUtil;
import net.lab1024.sa.common.common.util.SmartEnumUtil;
import net.lab1024.sa.common.common.util.SmartPageUtil;
import net.lab1024.sa.common.constant.RedisKeyConst;
import net.lab1024.sa.common.module.support.file.constant.FileFolderTypeEnum;
import net.lab1024.sa.common.module.support.file.dao.FileDao;
import net.lab1024.sa.common.module.support.file.domain.entity.FileEntity;
import net.lab1024.sa.common.module.support.file.domain.form.FileQueryForm;
import net.lab1024.sa.common.module.support.file.domain.form.FileUrlUploadForm;
import net.lab1024.sa.common.module.support.file.domain.vo.FileDownloadVO;
import net.lab1024.sa.common.module.support.file.domain.vo.FileMetadataVO;
import net.lab1024.sa.common.module.support.file.domain.vo.FileUploadVO;
import net.lab1024.sa.common.module.support.file.domain.vo.FileVO;
import net.lab1024.sa.common.module.support.redis.RedisService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ????????????
 *
 * @Author 1024???????????????: ??????
 * @Date 2019???10???11??? 15:34:47
 * @Wechat zhuoda1024
 * @Email lab1024@163.com
 * @Copyright 1024??????????????? ??? https://1024lab.net ???
 */
@Service
public class FileService {

    /**
     * ?????????????????????
     */
    private static final int FILE_NAME_MAX_LENGTH = 100;

    @Resource
    private IFileStorageService fileStorageService;

    @Autowired
    private FileDao fileDao;

    @Autowired
    private RedisService redisService;

    @Value("${spring.servlet.multipart.max-file-size}")
    private String maxFileSize;

    /**
     * ??????????????????????????? url ??????
     *
     * @param urlUploadForm
     * @param requestUser
     * @return
     */
    public ResponseDTO<FileUploadVO> fileUpload(FileUrlUploadForm urlUploadForm, RequestUser requestUser) {
        try {
            URL url = new URL(urlUploadForm.getFileUrl());
            URLConnection urlConnection = url.openConnection();
            // ??????????????????
            String contentType = urlConnection.getContentType();
            String fileType = fileStorageService.getFileTypeByContentType(contentType);
            // ????????????key
            String fileKey = fileStorageService.generateFileNameByType(fileType);
            MockMultipartFile file = new MockMultipartFile(fileKey, fileKey, contentType, urlConnection.getInputStream());
            return this.fileUpload(file, urlUploadForm.getFolder(), requestUser);
        } catch (IOException e) {
            return ResponseDTO.error(SystemErrorCode.SYSTEM_ERROR, "????????????");
        }
    }

    /**
     * ??????????????????
     *
     * @param file
     * @param folderType ???????????????
     * @return
     */
    public ResponseDTO<FileUploadVO> fileUpload(MultipartFile file, Integer folderType, RequestUser requestUser) {
        FileFolderTypeEnum folderTypeEnum = SmartEnumUtil.getEnumByValue(folderType, FileFolderTypeEnum.class);
        if (null == folderTypeEnum) {
            return ResponseDTO.userErrorParam("???????????????");
        }
        if (null == file || file.getSize() == 0) {
            return ResponseDTO.userErrorParam("????????????????????????");
        }
        // ??????????????????
        String originalFilename = file.getOriginalFilename();
        if (StringUtils.isBlank(originalFilename)) {
            return ResponseDTO.userErrorParam("??????????????????????????????");
        }
        if (originalFilename.length() > FILE_NAME_MAX_LENGTH) {
            return ResponseDTO.userErrorParam("??????????????????????????????" + FILE_NAME_MAX_LENGTH);
        }
        // ??????????????????
        String maxSizeStr = maxFileSize.toLowerCase().replace("mb", "");
        long maxSize = Integer.parseInt(maxSizeStr) * 1024 * 1024L;
        if (file.getSize() > maxSize) {
            return ResponseDTO.userErrorParam("??????????????????:" + maxSize);
        }
        // ??????????????????
        ResponseDTO<FileUploadVO> response = fileStorageService.fileUpload(file, folderTypeEnum.getFolder());
        if (!response.getOk()) {
            return response;
        }

        // ???????????? ?????????????????????
        FileUploadVO uploadVO = response.getData();

        FileEntity fileEntity = new FileEntity();
        fileEntity.setFolderType(folderTypeEnum.getValue());
        fileEntity.setFileName(originalFilename);
        fileEntity.setFileSize(file.getSize());
        fileEntity.setFileKey(uploadVO.getFileKey());
        fileEntity.setFileType(uploadVO.getFileType());
        fileEntity.setCreatorId(requestUser == null ? null:requestUser.getUserId());
        fileEntity.setCreatorName(requestUser == null ? null:requestUser.getUserName());
        fileEntity.setCreatorUserType(requestUser == null ? null:requestUser.getUserType().getValue());
        fileDao.insert(fileEntity);
        uploadVO.setFileId(fileEntity.getFileId());
        // ????????????
        String redisKey = redisService.generateRedisKey(RedisKeyConst.Support.FILE_URL, uploadVO.getFileKey());
        redisService.set(redisKey, uploadVO.getFileUrl(), fileStorageService.cacheExpireSecond());

        String fileRedisKey = redisService.generateRedisKey(RedisKeyConst.Support.FILE_VO, uploadVO.getFileKey());
        FileVO fileVO = SmartBeanUtil.copy(fileEntity, FileVO.class);
        redisService.set(fileRedisKey, fileVO, fileStorageService.cacheExpireSecond());
        return response;
    }

    public List<FileVO> getFileList(List<String> fileKeyList) {
        if (CollectionUtils.isEmpty(fileKeyList)) {
            return Lists.newArrayList();
        }
        return fileKeyList.stream().map(this::getCacheFileVO).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private FileVO getCacheFileVO(String fileKey) {
        String redisKey = redisService.generateRedisKey(RedisKeyConst.Support.FILE_VO, fileKey);
        FileVO fileVO = redisService.getObject(redisKey, FileVO.class);
        if (fileVO == null) {
            fileVO = fileDao.getByFileKey(fileKey);
            if (fileVO == null) {
                return null;
            }
            redisService.set(redisKey, fileVO, fileStorageService.cacheExpireSecond());
        }
        fileVO.setFileUrl(this.getCacheUrl(fileKey));
        return fileVO;
    }

    /**
     * ???????????????????????? ????????????URL
     * ???????????? key ?????????????????????
     *
     * @param fileKey
     * @return
     */
    public ResponseDTO<String> getFileUrl(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            return ResponseDTO.error(UserErrorCode.PARAM_ERROR);
        }
        // ??????????????????????????????
        String keyList = StrUtil.split(fileKey, StringConst.SEPARATOR)
                .stream().map(this::getCacheUrl)
                .collect(Collectors.joining(StringConst.SEPARATOR));
        return ResponseDTO.ok(keyList);
    }


    private String getCacheUrl(String fileKey) {
        String redisKey = redisService.generateRedisKey(RedisKeyConst.Support.FILE_URL, fileKey);
        String fileUrl = redisService.get(redisKey);
        if (null != fileUrl) {
            return fileUrl;
        }
        ResponseDTO<String> responseDTO = fileStorageService.getFileUrl(fileKey);
        if (!responseDTO.getOk()) {
            return null;
        }
        fileUrl = responseDTO.getData();
        redisService.set(redisKey, fileUrl, fileStorageService.cacheExpireSecond());
        return fileUrl;
    }

    /**
     * ????????????
     *
     * @param queryForm
     * @return
     */
    public PageResult<FileVO> queryPage(FileQueryForm queryForm) {
        Page<?> page = SmartPageUtil.convert2PageQuery(queryForm);
        List<FileVO> list = fileDao.queryPage(page, queryForm);
        PageResult<FileVO> pageResult = SmartPageUtil.convert2PageResult(page, list);
        return pageResult;
    }

    /**
     * ???????????????????????? ??? FileKey ????????????
     *
     * @param fileKey
     * @return
     * @throws IOException
     */
    public ResponseEntity<Object> downloadByFileKey(String fileKey, String userAgent) {
        // ????????????????????? ???????????????????????? ?????? url
        ResponseDTO<FileDownloadVO> responseDTO = fileStorageService.fileDownload(fileKey);
        if (!responseDTO.getOk()) {
            HttpHeaders heads = new HttpHeaders();
            heads.add(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8");
            return new ResponseEntity<>(responseDTO.getMsg() + "???" + fileKey, heads, HttpStatus.OK);
        }
        // ???????????????
        HttpHeaders heads = new HttpHeaders();
        heads.add(HttpHeaders.CONTENT_TYPE, "application/octet-stream; charset=utf-8");
        // ??????????????????????????????????????????
        FileDownloadVO fileDownloadVO = responseDTO.getData();
        FileMetadataVO metadata = fileDownloadVO.getMetadata();
        String fileName = null != metadata ? metadata.getFileName() : fileKey.substring(fileKey.lastIndexOf("/"));
        fileName = fileStorageService.getDownloadFileNameByUA(fileName, userAgent);
        heads.add(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + fileName);
        ResponseEntity<Object> responseEntity = new ResponseEntity<>(fileDownloadVO.getData(), heads, HttpStatus.OK);
        return responseEntity;
    }

    /**
     * ????????????key ??????
     *
     * @param fileKey
     * @return
     */
    public ResponseDTO<String> deleteByFileKey(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            return ResponseDTO.error(UserErrorCode.PARAM_ERROR);
        }
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileKey(fileKey);
        fileEntity = fileDao.selectOne(new QueryWrapper<>(fileEntity));
        if (null == fileEntity) {
            return ResponseDTO.error(UserErrorCode.DATA_NOT_EXIST);
        }
        // ????????????????????? ???????????????????????? ????????????
        return fileStorageService.delete(fileKey);
    }
}
