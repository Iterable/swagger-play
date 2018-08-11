/**
 * Copyright 2017 SmartBear Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package play.modules.swagger

import java.io.File

import io.swagger.config.FilterFactory
import io.swagger.config.ScannerFactory
import io.swagger.core.filter.SwaggerSpecFilter
import javax.inject.Inject
import play.api.Configuration
import play.api.Environment
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.modules.swagger.util.SwaggerContext
import play.routes.compiler.RoutesFileParser
import play.routes.compiler.StaticPart
import play.routes.compiler.{Include => PlayInclude}
import play.routes.compiler.{Route => PlayRoute}

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.io.Source

trait SwaggerPlugin

class SwaggerPluginImpl @Inject()(lifecycle: ApplicationLifecycle, env: Environment, config: Configuration) extends SwaggerPlugin {

  val logger = Logger("swagger")

  logger.info("Swagger - starting initialisation...")

  val apiVersion = config.get[String]("api.version")

  val basePath = config.get[String]("swagger.api.basepath")

  val host = config.get[String]("swagger.api.host")

  val title = config.get[String]("swagger.api.info.title")

  val description = config.get[String]("swagger.api.info.description")

  val termsOfServiceUrl = config.get[String]("swagger.api.info.termsOfServiceUrl")

  val contact = config.get[String]("swagger.api.info.contact")

  val license = config.get[String]("swagger.api.info.license")

  val licenseUrl = config.get[String]("swagger.api.info.licenseUrl")

  SwaggerContext.registerClassLoader(env.classLoader)

  val scanner = new PlayApiScanner()
  ScannerFactory.setScanner(scanner)

  val swaggerConfig = new PlaySwaggerConfig()

  swaggerConfig.description = description
  swaggerConfig.basePath = basePath
  swaggerConfig.contact = contact
  swaggerConfig.version = apiVersion
  swaggerConfig.title = title
  swaggerConfig.host = host
  swaggerConfig.termsOfServiceUrl = termsOfServiceUrl
  swaggerConfig.license = license
  swaggerConfig.licenseUrl = licenseUrl

  PlayConfigFactory.setConfig(swaggerConfig)

  val routes = parseRoutes

  def parseRoutes: List[PlayRoute] = {
    def playRoutesClassNameToFileName(className: String) = className.replace(".Routes", ".routes")

    val routesFile = config.get[Option[String]]("play.http.router") match {
      case None => "routes"
      case Some(value) => playRoutesClassNameToFileName(value)
    }
    //Parses multiple route files recursively
    def parseRoutesHelper(routesFile: String, prefix: String): List[PlayRoute] = {
      logger.debug(s"Processing route file '$routesFile' with prefix '$prefix'")

      val routesContent =  Source.fromInputStream(env.classLoader.getResourceAsStream(routesFile)).mkString
      val parsedRoutes = RoutesFileParser.parseContent(routesContent,new File(routesFile))
      val routes = parsedRoutes.right.get.collect {
        case route: PlayRoute =>
          logger.debug(s"Adding route '$route'")
          Seq(route.copy(path = route.path.copy(parts = StaticPart(prefix) +: route.path.parts)))
        case include: PlayInclude =>
          logger.debug(s"Processing route include $include")
          parseRoutesHelper(playRoutesClassNameToFileName(include.router), include.prefix)
      }.flatten
      logger.debug(s"Finished processing route file '$routesFile'")
      routes
    }
    parseRoutesHelper(routesFile, "")
  }

  val routesRules = Map(routes map
    { route =>
    {
      val routeName = s"${route.call.packageName}.${route.call.controller}$$.${route.call.method}"
      routeName -> route
    }
    } : _*)

  val route = new RouteWrapper(routesRules)
  RouteFactory.setRoute(route)
  config.get[Option[String]]("swagger.filter") match {
    case Some(e) if e.nonEmpty =>
      try {
        FilterFactory setFilter SwaggerContext.loadClass(e).newInstance.asInstanceOf[SwaggerSpecFilter]
        logger.debug("Setting swagger.filter to %s".format(e))
      } catch {
        case ex: Exception => Logger("swagger").error("Failed to load filter " + e, ex)
      }
    case _ =>
  }

  val docRoot = ""
  ApiListingCache.listing(docRoot, "127.0.0.1")

  logger.info("Swagger - initialization done.")

  lifecycle.addStopHook { () =>
    ApiListingCache.cache = None
    logger.info("Swagger - stopped.")

    Future.successful(())
  }

}
