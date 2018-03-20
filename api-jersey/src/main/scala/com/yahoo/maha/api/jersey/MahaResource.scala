// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.api.jersey

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType}
import javax.ws.rs.{Path, Produces, _}

import com.yahoo.maha.core._
import com.yahoo.maha.core.bucketing.{BucketParams, UserInfo}
import com.yahoo.maha.core.request.{BaseRequest, ReportingRequest}
import com.yahoo.maha.core.{RequestModel, Schema}
import com.yahoo.maha.parrequest.GeneralError
import com.yahoo.maha.service.utils.MahaConstants
import com.yahoo.maha.service.{MahaRequestProcessor, MahaService, RequestResult}
import grizzled.slf4j.Logging
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.MDC
import org.springframework.stereotype.Component

import scala.util.Try

@Path("/registry")
@Component
class MahaResource(mahaService: MahaService, baseRequest: BaseRequest) extends Logging {

  @GET
  @Path("/{registryName}/domain")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def getDomain(@PathParam("registryName") registryName: String): String = {
    val domainjson: Option[String] = mahaService.getDomain(registryName)
    if(domainjson.isDefined) {
      domainjson.get
    } else {
      throw NotFoundException(Error(s"registry $registryName not found"))
    }
  }

  @GET
  @Path("/{registryName}/domain/cubes/{cube}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def getDomainForCube(@PathParam("registryName") registryName: String, @PathParam("cube") cube: String): String = {
    val domainjson: Option[String] = mahaService.getDomainForCube(registryName, cube)
    if(domainjson.isDefined) {
      domainjson.get
    } else {
      throw NotFoundException(Error(s"registry $registryName and cube $cube not found"))
    }
  }

  @GET
  @Path("/{registryName}/flattenDomain")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def getFlattenDomain(@PathParam("registryName") registryName: String): String = {
    val domainjson: Option[String] = mahaService.getFlattenDomain(registryName)
    if(domainjson.isDefined) {
      domainjson.get
    } else {
      throw NotFoundException(Error(s"registry $registryName not found"))
    }
  }

  @GET
  @Path("/{registryName}/flattenDomain/cubes/{cube}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def getFlattenDomainForCube(@PathParam("registryName") registryName: String, @PathParam("cube") cube: String): String = {
    val domainjson: Option[String] = mahaService.getFlattenDomainForCube(registryName, cube)
    if(domainjson.isDefined) {
      domainjson.get
    } else {
      throw NotFoundException(Error(s"registry $registryName and cube $cube not found"))
    }
  }

  @GET
  @Path("/{registryName}/flattenDomain/cubes/{cube}/{revision}")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def getFlattenDomainForCube(@PathParam("registryName") registryName: String, @PathParam("cube") cube: String, @PathParam("revision") revision: Int): String = {
    val domainjson: Option[String] = mahaService.getFlattenDomainForCube(registryName, cube, Option(revision))
    if(domainjson.isDefined) {
      domainjson.get
    } else {
      throw NotFoundException(Error(s"registry $registryName and cube $cube with revision $revision not found"))
    }
  }

  @POST
  @Path("/{registryName}/schemas/{schema}/query")
  @Produces(Array(MediaType.APPLICATION_JSON))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  def query(@PathParam("registryName") registryName: String,
            @PathParam("schema") schema: String,
            @QueryParam("debug") @DefaultValue("false") debug: Boolean,
            @QueryParam("forceEngine") forceEngine: String,
            @QueryParam("forceRevision") forceRevision: Int,
            @Context httpServletRequest: HttpServletRequest,
            @Suspended response: AsyncResponse) = {

    info(s"registryName: $registryName, schema: $schema, forceEngine: $forceEngine, forceRevision: $forceRevision")
    val schemaOption: Option[Schema] = Schema.withNameInsensitiveOption(schema)

    if(!schemaOption.isDefined) {
      throw NotFoundException(Error(s"schema $schema not found"))
    }

    val (reportingRequest: ReportingRequest, rawJson: Array[Byte]) = createReportingRequest(httpServletRequest, schemaOption.get, debug, forceEngine)
    val bucketParams: BucketParams = BucketParams(UserInfo(MDC.get(MahaConstants.USER_ID), Try(MDC.get(MahaConstants.IS_INTERNAL).toBoolean).getOrElse(false)), forceRevision = Option(forceRevision))
    val mahaRequestProcessor: MahaRequestProcessor = MahaRequestProcessor(registryName, mahaService)

    mahaRequestProcessor.onSuccess((requestModel: RequestModel, requestResult: RequestResult) => {
      response.resume(JsonStreamingOutput(requestModel, requestResult.rowList))
    })

    mahaRequestProcessor.onFailure((ge: GeneralError) => {
      if(ge.throwableOption.isDefined) {
        response.resume(ge.throwableOption.get())
      } else {
        response.resume(new Exception(ge.message))
      }
    })

    mahaRequestProcessor.process(bucketParams, reportingRequest, rawJson)

  }

  private def createReportingRequest(httpServletRequest: HttpServletRequest, schema: Schema, debug: Boolean = false, forceEngine: String = "") : (ReportingRequest, Array[Byte]) = {
    val rawJson = IOUtils.toByteArray(httpServletRequest.getInputStream)
    val reportingRequestResult = baseRequest.deserializeSync(rawJson, schema)
    require(reportingRequestResult.isSuccess, reportingRequestResult.toString)
    val originalRequest = reportingRequestResult.toOption.get
    val request = {
      if(!debug && StringUtils.isBlank(forceEngine)) {
        originalRequest
      } else {
        val withDebug = if(debug) {
          ReportingRequest.enableDebug(originalRequest)
        } else {
          originalRequest
        }
        val withEngine = if(StringUtils.isNotBlank(forceEngine)) {
          Engine.from(forceEngine).fold(withDebug) {
            case OracleEngine => ReportingRequest.forceOracle(withDebug)
            case DruidEngine => ReportingRequest.forceDruid(withDebug)
            case HiveEngine => ReportingRequest.forceHive(withDebug)
            case _ => withDebug
          }
        } else {
          withDebug
        }
        withEngine
      }
    }
    (request, rawJson)
  }

}
