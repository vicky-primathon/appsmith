package com.appsmith.server.solutions;

import com.appsmith.external.models.BaseDomain;
import com.appsmith.external.models.DBAuth;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.ApplicationJSONFile;
import com.appsmith.server.domains.ApplicationPage;
import com.appsmith.server.domains.Datasource;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.PluginType;
import com.appsmith.server.domains.User;
import com.appsmith.server.dtos.ActionDTO;
import com.appsmith.server.dtos.ResponseDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.repositories.DatasourceRepository;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.PluginRepository;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.ApplicationService;
import com.appsmith.server.services.DatasourceService;
import com.appsmith.server.services.NewActionService;
import com.appsmith.server.services.NewPageService;
import com.appsmith.server.services.OrganizationService;
import com.appsmith.server.services.SequenceService;
import com.appsmith.server.services.SessionUserService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.bson.types.ObjectId;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportExportApplicationService {
    private final DatasourceService datasourceService;
    private final SessionUserService sessionUserService;
    private final NewActionRepository newActionRepository;
    private final DatasourceRepository datasourceRepository;
    private final PluginRepository pluginRepository;
    private final OrganizationService organizationService;
    private final ApplicationService applicationService;
    private final NewPageService newPageService;
    private final ApplicationPageService applicationPageService;
    private final NewPageRepository newPageRepository;
    private final NewActionService newActionService;
    private final SequenceService sequenceService;
    private final ExamplesOrganizationCloner examplesOrganizationCloner;

    private static final Set<MediaType> ALLOWED_CONTENT_TYPES = Set.of(MediaType.APPLICATION_JSON);

    public Mono<ApplicationJSONFile> getApplicationFileById(String applicationId) {
        ApplicationJSONFile file = new ApplicationJSONFile();
        Map<String, String> pluginMap = new HashMap<>();
        Map<String, String> datasourceMap = new HashMap<>();
        Map<String, String> newPageIdMap = new HashMap<>();

        return pluginRepository
                .findAll()
                .collectList()
                .map(pluginList -> {
                    pluginList.forEach(plugin -> pluginMap.put(plugin.getId(), plugin.getPackageName()));
                    return pluginList;
                })
                .flatMap(ignore -> applicationService.findById(applicationId, AclPermission.READ_APPLICATIONS))
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION, applicationId)))
                .flatMap(application -> {
                    ApplicationPage defaultPage = application.getPages()
                            .stream()
                            .filter(ApplicationPage::getIsDefault)
                            .findFirst()
                            .orElse(null);

                    if(defaultPage == null) {
                        return Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.DEFAULT_PAGE_NAME));
                    }
                    return Mono.zip(
                            newPageRepository.findById(defaultPage.getId(), AclPermission.READ_PAGES),
                            Mono.just(defaultPage),
                            Mono.just(application)
                    );
                })
                .flatMap(tuple -> {
                    NewPage defaultPage = tuple.getT1();
                    ApplicationPage defaultPageRef = tuple.getT2();
                    Application application = tuple.getT3();

                    defaultPageRef.setId(defaultPage.getUnpublishedPage().getName());
                    final String organizationId = application.getOrganizationId();
                    application.setOrganizationId(null);
                    examplesOrganizationCloner.makePristine(application);
                    String applicationName = application.getName();
                    file.setExportedApplication(application);
                    return newPageRepository.findByApplicationId(applicationId, AclPermission.READ_PAGES)
                            .collectList()
                            .flatMap(newPageList -> {
                                Map<String, Set<String>> mongoEscapedWidgetsNames = new HashMap<>();
                                newPageList.forEach(newPage -> {
                                    newPageIdMap.put(newPage.getId(), newPage.getUnpublishedPage().getName());
                                    newPage.setApplicationId(applicationName);
                                    examplesOrganizationCloner.makePristine(newPage);

                                    if(newPage.getUnpublishedPage().getLayouts() !=  null) {
                                        newPage.getUnpublishedPage().getLayouts().forEach(layout ->
                                            mongoEscapedWidgetsNames.put(layout.getId(), layout.getMongoEscapedWidgetNames())
                                        );
                                    }

                                    if(newPage.getPublishedPage() !=  null
                                        && newPage.getPublishedPage().getLayouts() !=  null) {

                                        newPage.getPublishedPage().getLayouts().forEach(layout ->
                                            mongoEscapedWidgetsNames.put(layout.getId(), layout.getMongoEscapedWidgetNames())
                                        );
                                    }
                                });
                                file.setPageList(newPageList);
                                file.setMongoEscapedWidgets(mongoEscapedWidgetsNames);
                                return datasourceRepository.findAllByOrganizationId(organizationId, AclPermission.READ_DATASOURCES)
                                        .collectList();
                            })
                            .map(datasourceList -> {
                                Map<String, String> decryptedFields = new HashMap<>();
                                //TODO Only export those are used in the app instead of org level
                                datasourceList.forEach(datasource -> {

                                    final DBAuth authentication = datasource.getDatasourceConfiguration() == null
                                            ? null : (DBAuth) datasource.getDatasourceConfiguration().getAuthentication();

                                    if (authentication != null) {
                                        authentication.setIsAuthorized(null);
                                        decryptedFields.put(authentication.getDatabaseName(), authentication.getPassword());
                                    }

                                    datasource.setPluginId(pluginMap.get(datasource.getPluginId()));

                                    datasourceMap.put(datasource.getId(), datasource.getName());
                                    datasource.setId(null);
                                    datasource.setOrganizationId(null);
                                });
                                file.setDecryptedFields(decryptedFields);
                                file.setDatasourceList(datasourceList);
                                return newActionRepository.findByApplicationId(applicationId);
                            })
                            .flatMap(Flux::collectList)
                            .map(newActionList -> {
                                Set<String> concernedDBNames = new HashSet<>();
                                newActionList.forEach(newAction -> {
                                    newAction.setPluginId(pluginMap.get(newAction.getPluginId()));
                                    newAction.setOrganizationId(null);
                                    newAction.setPolicies(null);
                                    newAction.setApplicationId(applicationName);
                                    if(newAction.getPluginType() == PluginType.DB) {
                                        concernedDBNames.add(mapDatasourceIdToNewAction(newAction.getPublishedAction(), datasourceMap));
                                        concernedDBNames.add(mapDatasourceIdToNewAction(newAction.getUnpublishedAction(), datasourceMap));
                                    }
                                    if(newAction.getUnpublishedAction() != null) {
                                        ActionDTO actionDTO = newAction.getUnpublishedAction();
                                        actionDTO.setPageId(newPageIdMap.get(actionDTO.getPageId()));
                                    }
                                });
                                file.setActionList(newActionList);
                                //log.debug("Exported datasources : {}",file.getDatasourceList());
                                file.getDatasourceList().removeIf(datasource -> !concernedDBNames.contains(datasource.getName()));
                                return Mono.just(file);
                            });
                })
                .then()
                .thenReturn(file);
    }

    public Mono<Application> extractFileAndSaveApplication(String orgId, Part filePart) {

        final MediaType contentType = filePart.headers().getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return Mono.error(new AppsmithException(
                    AppsmithError.VALIDATION_FAILURE,
                    "Please upload a valid application file. Only JSON type is allowed."
            ));
        }

        final Flux<DataBuffer> contentCache = filePart.content().cache();
        Mono<String> stringifiedFile = contentCache.count()
                .defaultIfEmpty(0L)
                .flatMap(count -> {
                    // Default implementation for the BufferFactory used breaks down the FilePart into chunks of 4KB.
                    // So we multiply the count of chunks with 4 to get an estimate on the file size in KB.
                    return DataBufferUtils.join(contentCache);
                })
                .map(dataBuffer -> {
                    byte[] data = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(data);
                    DataBufferUtils.release(dataBuffer);
                    return new String(data);
                });

        return stringifiedFile
                .flatMap(data -> {
                    Gson gson = new Gson();
                    Type fileType = new TypeToken<ResponseDTO<ApplicationJSONFile>>() {}.getType();
                    gson.toJson(data);
                    ResponseDTO<ApplicationJSONFile> jsonFile = gson.fromJson(data, fileType);
                    return importApplicationInOrganization(orgId, jsonFile.getData());
                });
    }
    public Mono<Application> importApplicationInOrganization(String orgId, ApplicationJSONFile importedDoc) {
        Map<String, String> pluginMap = new HashMap<>();
        Map<String, String> datasourceMap = new HashMap<>();
        Map<String, NewPage> pageNameMap = new HashMap<>();
        Map<String, String> actionIdMap = new HashMap<>();

        Application importedApplication = importedDoc.getExportedApplication();
        List<Datasource> importedDatasourceList = importedDoc.getDatasourceList();
        List<NewPage> importedNewPageList = importedDoc.getPageList();
        List<NewAction> importedNewActionList = importedDoc.getActionList();

        Mono<User> currUserMono = sessionUserService.getCurrentUser();
        final Flux<Datasource> existingDatasourceFlux = datasourceRepository.findAllByOrganizationId(orgId).cache();

        if(importedNewPageList.isEmpty()) {
            new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.PAGE, importedNewPageList);
        }

        return organizationService.findById(orgId, AclPermission.ORGANIZATION_MANAGE_APPLICATIONS)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.ORGANIZATION, orgId)))
                .flatMap(organization ->
                        pluginRepository.findAll()
                                .collectList()
                                .map(pluginList -> {
                                    pluginList.forEach(plugin -> pluginMap.put(plugin.getPackageName(), plugin.getId()));
                                    return pluginList;
                                })
                )
                .flatMap(pluginList -> Flux.fromIterable(importedDatasourceList)
                            //TODO check for duplicate datasources to avoid duplicates in target organization
                            .flatMap(datasource -> {
                                datasource.setPluginId(pluginMap.get(datasource.getPluginId()));
                                datasource.setOrganizationId(orgId);
                                final DBAuth authentication = datasource.getDatasourceConfiguration() == null
                                        ? null : (DBAuth) datasource.getDatasourceConfiguration().getAuthentication();
                                if(authentication != null) {
                                    authentication.setPassword(importedDoc.getDecryptedFields().get(authentication.getDatabaseName()));
                                }

                                return createUniqueDatasourceIfNotPresent(existingDatasourceFlux, datasource, orgId);
                            })
                            .map(datasource -> {
                                datasourceMap.put(datasource.getName(), datasource.getId());
                                return datasource;
                            })
                            .collectList()
                )
                .flatMap(ignored -> {
                    ApplicationPage defaultPage = importedApplication.getPages()
                            .stream()
                            .filter(ApplicationPage::getIsDefault)
                            .findFirst()
                            .get();
                    importedApplication.setPages(null);

                    return applicationPageService.setApplicationPolicies(currUserMono, orgId, importedApplication)
                            .flatMap(application -> applicationService.findByOrganizationId(orgId, AclPermission.MANAGE_APPLICATIONS)
                                    .collectList()
                                    .flatMap(applicationList -> {

                                        Application duplicateNameApp = applicationList
                                                .stream()
                                                .filter(application1 -> StringUtils.equals(application1.getName(), application.getName()))
                                                .findAny()
                                                .orElse(null);

                                        return getUniqueSuffixForDuplicateNameEntity(duplicateNameApp, orgId)
                                                .map(suffix -> {
                                                    importedApplication.setName(importedApplication.getName() + suffix);
                                                    return importedApplication;
                                                });
                                    })
                                    .then(Mono.zip(Mono.just(defaultPage), applicationService.save(importedApplication)))
                            );
                })
                .flatMap(tuple -> {
                    String defaultPageName = tuple.getT1().getId();
                    Application savedApp = tuple.getT2();
                    importedApplication.setId(savedApp.getId());
                    importedNewPageList.forEach(newPage -> newPage.setApplicationId(savedApp.getId()));

                    return importAndSavePages(importedNewPageList, importedApplication, importedDoc.getMongoEscapedWidgets())
                            .map(newPage -> {
                                ApplicationPage tempPage = new ApplicationPage();
                                pageNameMap.put(newPage.getUnpublishedPage().getName(), newPage);
                                tempPage.setIsDefault(StringUtils.equals(newPage.getUnpublishedPage().getName(), defaultPageName));
                                tempPage.setId(newPage.getId());
                                return tempPage;
                            })
                            .collectList();
                })
                .flatMap(importedApplicationPages -> {
                    importedApplication.setPages(importedApplicationPages);

                    importedNewActionList.forEach(newAction -> {
                        NewPage parentPage = pageNameMap.get(newAction.getUnpublishedAction().getPageId());
                        actionIdMap.put(newAction.getUnpublishedAction().getName(), newAction.getId());

                        examplesOrganizationCloner.makePristine(newAction);
                        newAction.setOrganizationId(orgId);
                        newAction.setApplicationId(importedApplication.getId());
                        newAction.setPluginId(pluginMap.get(newAction.getPluginId()));
                        newAction.getUnpublishedAction().setPageId(parentPage.getId());
                        mapDatasourceIdToNewAction(newAction.getUnpublishedAction(), datasourceMap);

                        if(newAction.getPublishedAction() != null) {
                            newAction.getPublishedAction().setPageId(parentPage.getId());
                            mapDatasourceIdToNewAction(newAction.getPublishedAction(), datasourceMap);
                        }
                        newActionService.generateAndSetActionPolicies(parentPage, newAction);

                    });
                    return newActionService.saveAll(importedNewActionList)
                            .map(newAction -> {
                                actionIdMap.put(actionIdMap.get(newAction.getUnpublishedAction().getName()), newAction.getId());
                                return newAction;
                            })
                            .then(Mono.just(importedApplication));
                })
                .flatMap(ignore -> {
                    importedNewPageList.forEach(page -> mapActionIdWithPageLayout(page, actionIdMap));
                    return Flux.fromIterable(importedNewPageList)
                            //TODO id for layoutOnLoadActions not updating for save() method
                            .flatMap(newPageService::save)
                            .collectList()
                            .flatMap(finalPageList -> applicationService.update(importedApplication.getId(),importedApplication));
                });
    }

    private Mono<String> getUniqueSuffixForDuplicateNameEntity(BaseDomain sourceEntity, String orgId) {
        if(sourceEntity != null) {
            return sequenceService
                    .getNextAsSuffix(sourceEntity.getClass(), " for organization with _id : " + orgId)
                    .flatMap(sequenceNumber -> Mono.just(" #" + sequenceNumber.trim()));
        }
        return Mono.just("");
    }

    private Flux<NewPage> importAndSavePages(List<NewPage> pages, Application application, Map<String, Set<String>> mongoEscapedWidget) {

        pages.forEach(newPage -> {
            newPage.getUnpublishedPage().setApplicationId(application.getId());
            applicationPageService.generateAndSetPagePolicies(application, newPage.getUnpublishedPage());
            newPage.setPolicies(newPage.getUnpublishedPage().getPolicies());
            if(mongoEscapedWidget != null) {
                newPage.getUnpublishedPage().getLayouts().forEach(layout -> {
                    layout.setMongoEscapedWidgetNames(mongoEscapedWidget.get(layout.getId()));
                    layout.setId(new ObjectId().toString());
                });
            }

            if(newPage.getPublishedPage() != null) {
                newPage.getPublishedPage().setApplicationId(application.getId());
                applicationPageService.generateAndSetPagePolicies(application, newPage.getPublishedPage());
                if(mongoEscapedWidget != null) {
                    newPage.getPublishedPage().getLayouts().forEach(layout -> {
                            layout.setMongoEscapedWidgetNames(mongoEscapedWidget.get(layout.getId()));
                            layout.setId(new ObjectId().toString());
                    });
                }
            }
        });

        return Flux.fromIterable(pages)
                .flatMap(newPageService::save);
    }

    private String mapDatasourceIdToNewAction(ActionDTO actionDTO, Map<String, String> datasourceMap) {
        if (actionDTO != null
                && actionDTO.getDatasource() != null
                && actionDTO.getDatasource().getId() != null) {

            Datasource ds = actionDTO.getDatasource();
            ds.setId(datasourceMap.get(ds.getId()));
            ds.setOrganizationId(null);
            return ds.getId();
        }
        return "";
    }

    private void mapActionIdWithPageLayout(NewPage page, Map<String, String> actionIdMap) {
        if(page.getUnpublishedPage().getLayouts() != null) {

            page.getUnpublishedPage().getLayouts().forEach(layout -> {
                if(layout.getLayoutOnLoadActions() != null) {
                    layout.getLayoutOnLoadActions().forEach(onLoadAction -> onLoadAction
                            .forEach(dslActionDTO -> dslActionDTO.setId(actionIdMap.get(dslActionDTO.getId()))));
                }
            });
        }

        if(page.getPublishedPage() != null && page.getPublishedPage().getLayouts() != null) {

            page.getPublishedPage().getLayouts().forEach(layout -> {
                if(layout.getLayoutOnLoadActions() != null) {
                    layout.getLayoutOnLoadActions().forEach(onLoadAction -> onLoadAction
                            .forEach(dslActionDTO -> dslActionDTO.setId(actionIdMap.get(dslActionDTO.getId()))));
                }
            });
        }
    }

    private Mono<Datasource> createUniqueDatasourceIfNotPresent(Flux<Datasource> existingDatasourceFlux,
                                                                Datasource datasource,
                                                                String toOrgId) {

        return existingDatasourceFlux
                .map(ds -> {
                    final DBAuth auth = ds.getDatasourceConfiguration() == null
                            ? null : (DBAuth) ds.getDatasourceConfiguration().getAuthentication();
                    if (auth != null) {
                        DBAuth datasourceAuth = (DBAuth) datasource.getDatasourceConfiguration().getAuthentication();
                        auth.setIsAuthorized(null);
                        auth.setAuthenticationResponse(null);
                        auth.setAuthType(datasourceAuth.getAuthType());
                        auth.setAuthenticationType(datasourceAuth.getAuthenticationType());
                    }
                    return ds;
                })
                .filter(ds -> ds.softEquals(datasource))
                .next()  // Get the first matching datasource, we don't need more than one here.
                .switchIfEmpty(Mono.defer(() ->
                        // No matching existing datasource found, so create a new one.
                        datasourceService.findByNameAndOrganizationId(datasource.getName(), toOrgId, AclPermission.MANAGE_DATASOURCES)
                                .flatMap(duplicateNameDatasource -> getUniqueSuffixForDuplicateNameEntity(duplicateNameDatasource, toOrgId))
                                .map(suffix -> {
                                    datasource.setName(datasource.getName() + suffix);
                                    return datasource;
                                })
                                .then(datasourceService.create(datasource))
                ));
    }
}