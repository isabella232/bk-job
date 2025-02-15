package com.tencent.bk.job.file_gateway.api.esb;

import com.tencent.bk.job.common.constant.ErrorCode;
import com.tencent.bk.job.common.esb.model.EsbResp;
import com.tencent.bk.job.common.exception.InvalidParamException;
import com.tencent.bk.job.common.iam.exception.PermissionDeniedException;
import com.tencent.bk.job.common.iam.model.AuthResult;
import com.tencent.bk.job.common.model.dto.AppResourceScope;
import com.tencent.bk.job.common.service.AppScopeMappingService;
import com.tencent.bk.job.file_gateway.auth.FileSourceAuthService;
import com.tencent.bk.job.file_gateway.consts.WorkerSelectModeEnum;
import com.tencent.bk.job.file_gateway.consts.WorkerSelectScopeEnum;
import com.tencent.bk.job.file_gateway.model.dto.FileSourceDTO;
import com.tencent.bk.job.file_gateway.model.req.esb.v3.EsbCreateOrUpdateFileSourceV3Req;
import com.tencent.bk.job.file_gateway.model.resp.esb.v3.EsbFileSourceSimpleInfoV3DTO;
import com.tencent.bk.job.file_gateway.service.FileSourceService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@Slf4j
public class EsbFileSourceV3ResourceImpl implements EsbFileSourceV3Resource {

    private final FileSourceAuthService fileSourceAuthService;
    private final FileSourceService fileSourceService;
    private final AppScopeMappingService appScopeMappingService;

    @Autowired
    public EsbFileSourceV3ResourceImpl(FileSourceAuthService fileSourceAuthService,
                                       FileSourceService fileSourceService,
                                       AppScopeMappingService appScopeMappingService) {
        this.fileSourceAuthService = fileSourceAuthService;
        this.fileSourceService = fileSourceService;
        this.appScopeMappingService = appScopeMappingService;
    }

    @Override
    public EsbResp<EsbFileSourceSimpleInfoV3DTO> createFileSource(EsbCreateOrUpdateFileSourceV3Req req) {
        req.fillAppResourceScope(appScopeMappingService);
        Long appId = req.getAppId();
        String username = req.getUserName();
        checkCreateFileSourcePermission(username, req.getAppResourceScope());
        checkCreateParam(req);
        FileSourceDTO fileSourceDTO = buildFileSourceDTO(req.getUserName(), appId, req);
        Integer fileSourceId = fileSourceService.saveFileSource(appId, fileSourceDTO);
        boolean registerResult = fileSourceAuthService.registerFileSource(
            username, fileSourceId, fileSourceDTO.getAlias());
        if (!registerResult) {
            log.warn("Fail to register file_source to iam:({},{})", fileSourceId, fileSourceDTO.getAlias());
        }
        return EsbResp.buildSuccessResp(new EsbFileSourceSimpleInfoV3DTO(fileSourceId));
    }

    @Override
    public EsbResp<EsbFileSourceSimpleInfoV3DTO> updateFileSource(EsbCreateOrUpdateFileSourceV3Req req) {
        req.fillAppResourceScope(appScopeMappingService);
        checkUpdateParam(req);
        Long appId = req.getAppId();
        String username = req.getUserName();
        Integer id = req.getId();
        checkManageFileSourcePermission(username, req.getAppResourceScope(), id);
        FileSourceDTO fileSourceDTO = buildFileSourceDTO(req.getUserName(), appId, req);
        Integer fileSourceId = fileSourceService.updateFileSourceById(appId, fileSourceDTO);
        return EsbResp.buildSuccessResp(new EsbFileSourceSimpleInfoV3DTO(fileSourceId));
    }

    private void checkCreateParam(EsbCreateOrUpdateFileSourceV3Req req) {
        String code = req.getCode();
        if (StringUtils.isBlank(code)) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                new String[]{"code", "code cannot be null or blank"});
        }
        if (fileSourceService.existsCode(code)) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                new String[]{"code", String.format("code [%s] already exists", code)});
        }
        if (StringUtils.isBlank(req.getAlias())) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                new String[]{"alias", "alias cannot be null or blank"});
        }
        if (StringUtils.isBlank(req.getType())) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                new String[]{"type", "type cannot be null or blank"});
        }
        if (StringUtils.isBlank(req.getCredentialId())) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                new String[]{"credential_id", "credential_id cannot be null or blank"});
        }
    }

    private void checkUpdateParam(EsbCreateOrUpdateFileSourceV3Req req) {
        Long appId = req.getAppId();
        Integer id = req.getId();
        String code = req.getCode();
        if (id == null && StringUtils.isBlank(code)) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                new String[]{"id/code", "id and code cannot be null/blank simultaneously"});
        }
        if (id == null) {
            id = fileSourceService.getFileSourceIdByCode(appId, code);
            if (id == null) {
                throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                    new String[]{"code", String.format("cannot find fileSource by code [%s]", code)});
            }
        }
        req.setId(id);
        if (!fileSourceService.existsFileSource(appId, id)) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME_AND_REASON,
                new String[]{"bk_biz_id/id",
                    String.format("fileSource [%s] not exists in biz [%s]", id, appId)}
            );
        }
    }

    private FileSourceDTO buildFileSourceDTO(String username, Long appId,
                                             EsbCreateOrUpdateFileSourceV3Req fileSourceCreateUpdateReq) {
        FileSourceDTO fileSourceDTO = new FileSourceDTO();
        fileSourceDTO.setAppId(appId);
        fileSourceDTO.setId(fileSourceCreateUpdateReq.getId());
        fileSourceDTO.setCode(fileSourceCreateUpdateReq.getCode());
        fileSourceDTO.setAlias(fileSourceCreateUpdateReq.getAlias());
        fileSourceDTO.setStatus(null);
        fileSourceDTO.setFileSourceType(
            fileSourceService.getFileSourceTypeByCode(
                fileSourceCreateUpdateReq.getType()
            )
        );
        fileSourceDTO.setFileSourceInfoMap(fileSourceCreateUpdateReq.getAccessParams());
        fileSourceDTO.setPublicFlag(false);
        fileSourceDTO.setSharedAppIdList(Collections.emptyList());
        fileSourceDTO.setShareToAllApp(false);
        fileSourceDTO.setCredentialId(fileSourceCreateUpdateReq.getCredentialId());
        fileSourceDTO.setFilePrefix(fileSourceCreateUpdateReq.getFilePrefix());
        fileSourceDTO.setWorkerSelectScope(WorkerSelectScopeEnum.PUBLIC.name());
        fileSourceDTO.setWorkerSelectMode(WorkerSelectModeEnum.AUTO.name());
        fileSourceDTO.setWorkerId(null);
        // 文件源默认开启状态
        fileSourceDTO.setEnable(true);
        fileSourceDTO.setCreator(username);
        fileSourceDTO.setCreateTime(System.currentTimeMillis());
        fileSourceDTO.setLastModifyUser(username);
        fileSourceDTO.setLastModifyTime(System.currentTimeMillis());
        return fileSourceDTO;
    }

    public void checkCreateFileSourcePermission(String username, AppResourceScope appResourceScope) {
        // 需要拥有在业务下创建文件源的权限
        AuthResult authResult = fileSourceAuthService.authCreateFileSource(username, appResourceScope);
        if (!authResult.isPass()) {
            throw new PermissionDeniedException(authResult);
        }
    }

    public void checkManageFileSourcePermission(String username, AppResourceScope appResourceScope,
                                                Integer fileSourceId) {
        // 需要拥有在业务下管理某个具体文件源的权限
        AuthResult authResult = fileSourceAuthService.authManageFileSource(username, appResourceScope,
            fileSourceId, null);
        if (!authResult.isPass()) {
            throw new PermissionDeniedException(authResult);
        }
    }
}
