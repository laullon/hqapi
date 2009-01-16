
import org.hyperic.hq.hqapi1.ErrorCode

import org.hyperic.hq.auth.shared.SessionManager
import org.hyperic.hq.authz.shared.PermissionException
import org.hyperic.hq.appdef.shared.AppdefEntityID
import org.hyperic.hq.appdef.shared.AppdefEntityTypeID
import org.hyperic.hq.bizapp.server.session.EventsBossEJBImpl as EventsBoss
import org.hyperic.hq.events.AlertSeverity
import org.hyperic.hq.events.EventConstants
import org.hyperic.hq.events.shared.AlertConditionValue
import org.hyperic.hq.events.shared.AlertDefinitionValue
import org.hyperic.hq.measurement.shared.ResourceLogEvent
import org.hyperic.hq.product.LogTrackPlugin

public class AlertdefinitionController extends ApiController {
    private eventBoss   = EventsBoss.one
    private EVENT_LEVEL_TO_NUM = [
        ANY: -1,
        ERR : LogTrackPlugin.LOGLEVEL_ERROR,
        WRN : LogTrackPlugin.LOGLEVEL_WARN,
        INF : LogTrackPlugin.LOGLEVEL_INFO,
        DBG : LogTrackPlugin.LOGLEVEL_DEBUG,
    ]
    
    /**
     * Seems as though the measurementId column for alert conditions can
     * equal 0 (or something else not found in the DB?)
     *
     * We safely avoid any problems by returning 'Unknown' for templates
     * we can't find.
     */
    private getTemplate(int mid, typeBased) {
        if (typeBased) {
            try {
                return metricHelper.findTemplateById(mid)
            } catch (Exception e) {
                log.warn("Lookup of template id=${mid} failed", e)
            }
        }
        else {
            try {
                return metricHelper.findMeasurementById(mid).template
            } catch (Exception e) {
                log.warn("Lookup of metric id=${mid} failed", e)
            }
        }
        return null
    }

    private Closure getAlertDefinitionXML(d, excludeIds) {
        { out ->
            def attrs = [name: d.name,
                         description: d.description,
                         priority: d.priority,
                         active: d.active,
                         enabled: d.enabled,
                         frequency: d.frequencyType,
                         count: d.count,
                         range: d.range,
                         willRecover: d.willRecover,
                         notifyFiltered: d.notifyFiltered,
                         controlFiltered: d.controlFiltered]

            if (!excludeIds) {
                attrs['id'] = d.id
            }

            // parent is nullable.
            if (d.parent != null) {
                attrs['parent'] = d.parent.id
            }

            AlertDefinition(attrs) {

                if (d.resource) {
                    if (d.parent != null && d.parent.id == 0) {
                        ResourcePrototype(id: d.resource.id,
                                          name: d.resource.name)
                    } else {
                        Resource(id : d.resource.id,
                                 name : d.resource.name)
                    }
                }
                if (d.escalation) {
                    Escalation(id : d.escalation.id,
                               name : d.escalation.name)
                }
                for (c in d.conditions) {
                    // Attributes common to all conditions
                    def conditionAttrs = [required: c.required,
                                          type: c.type]

                    if (c.type == EventConstants.TYPE_THRESHOLD) {
                        def metric = getTemplate(c.measurementId, d.typeBased)
                        if (!metric) {
                            log.warn("Unable to find metric " + c.measurementId +
                                     "for definition " + d.name)
                            continue
                        } else {
                            conditionAttrs["thresholdMetric"] = metric.name
                            conditionAttrs["thresholdComparator"] = c.comparator
                            conditionAttrs["thresholdValue"] = c.threshold
                        }
                    } else if (c.type == EventConstants.TYPE_BASELINE) {
                        def metric = getTemplate(c.measurementId, d.typeBased)
                        if (!metric) {
                            log.warn("Unable to find metric " + c.measurementId +
                                     "for definition " + d.name)
                            continue
                        } else {
                            conditionAttrs["baselineMetric"] = metric.name
                            conditionAttrs["baselineComparator"] = c.comparator
                            conditionAttrs["baselinePercentage"] = c.threshold
                            conditionAttrs["baselineType"] = c.optionStatus
                        }
                    } else if (c.type == EventConstants.TYPE_CHANGE) {
                        def metric = getTemplate(c.measurementId, d.typeBased)
                        if (!metric) {
                            log.warn("Unable to find metric " + c.measurementId +
                                     "for definition " + d.name)
                            continue
                        } else {
                            conditionAttrs["metricChange"] = metric.name
                        }
                    } else if (c.type == EventConstants.TYPE_CUST_PROP) {
                        conditionAttrs["property"] = c.name
                    } else if (c.type == EventConstants.TYPE_LOG) {
                        int level = c.name.toInteger()
                        conditionAttrs["logLevel"] = ResourceLogEvent.getLevelString(level)
                        conditionAttrs["logMatches"] = c.optionStatus
                    } else if (c.type == EventConstants.TYPE_ALERT) {
                        def alert = alertHelper.getById(c.measurementId)
                        if (alert == null) {
                            log.warn("Unable to find recover condition " +
                                     c.measurementId + " for " + c.name)
                            continue
                        } else {
                            conditionAttrs["recover"] = alert.name
                        }
                    } else if (c.type == EventConstants.TYPE_CFG_CHG) {
                        conditionAttrs["configMatch"] = c.optionStatus
                    } else if (c.type == EventConstants.TYPE_CONTROL) {
                        conditionAttrs["controlAction"] = c.name
                        conditionAttrs["controlStatus"] = c.optionStatus
                    } else {
                        log.warn("Unhandled condition type " + c.type +
                                 " for condition " + c.name)
                    }
                    // Write it out
                    AlertCondition(conditionAttrs)
                }
            }
        }
    }

    def listDefinitions(params) {

        def excludeTypeBased = params.getOne('excludeTypeBased')?.toBoolean()
        if (excludeTypeBased == null) {
            excludeTypeBased = false;
        }
        def definitions = alertHelper.findDefinitions(AlertSeverity.LOW, null,
                                                      excludeTypeBased)

        renderXml() {
            out << AlertDefinitionsResponse() {
                out << getSuccessXML()
                for (definition in definitions) {
                    out << getAlertDefinitionXML(definition, false)
                }
            }
        }
    }

    def listTypeDefinitions(params) {
        def excludeIds = params.getOne('excludeIds')?.toBoolean()
        def definitions = alertHelper.findTypeBasedDefinitions()

        def noIds = false
        if (excludeIds) {
            noIds = true
        }

        renderXml() {
            out << AlertDefinitionsResponse() {
                out << getSuccessXML()
                for (definition in definitions) {
                    out << getAlertDefinitionXML(definition, noIds)
                }
            }
        }
    }

    def delete(params) {
        def id   = params.getOne("id")?.toInteger()

        def alertdefinition = alertHelper.getById(id)
        def failureXml = null

        if (!alertdefinition) {
            failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                       "Alert definition with id " + id +
                                       " not found")
        } else {
            try {
                alertdefinition.delete(user)
            } catch (PermissionException e) {
                failureXml = getFailureXML(ErrorCode.PERMISSION_DENIED)
            } catch (Exception e) {
                failureXml = getFailureXML(ErrorCode.UNEXPECTED_ERROR)
            }
        }

        renderXml() {
            StatusResponse() {
                if (failureXml) {
                    out << failureXml
                } else {
                    out << getSuccessXML()
                }
            }
        }
    }

    private checkRequiredAttributes(name, xml, attrs) {
        for (attr in attrs) {
            if (xml."@${attr}" == null) {
                return getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                     "Required attribute '" + attr +
                                     "' not given for " + name)
            }
        }
        return null
    }

    def sync(params) {
        def syncRequest = new XmlParser().parseText(getUpload('postdata'))
        def definitionsByName = [:]

        for (xmlDef in syncRequest['AlertDefinition']) {
            def failureXml = null
            def resource // Can be a resource or a prototype in the case of type alerts
            boolean typeBased
            def existing = null
            Integer id = xmlDef.'@id'?.toInteger()
            if (id) {
                existing = alertHelper.getById(id)
                if (!existing) {
                    failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                               "Definition with id " + id +
                                               " not found")
                } else {
                    typeBased = (existing.parent != null && existing.parent.id == 0)
                    resource = existing.resource
                }
            } else {
                if (xmlDef['Resource'].size() ==1 &&
                    xmlDef['ResourcePrototype'].size() == 1) {
                    failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                               "Only one of Resource or " +
                                               "ResourcePrototype required for " +
                                               xmlDef.'@name')
                } else if (xmlDef['Resource'].size() == 1) {
                    typeBased = false
                    def rid = xmlDef['Resource'][0].'@id'?.toInteger()
                    resource = getResource(rid)
                    if (!resource) {
                        failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                                   "Cannot find resource with " +
                                                   "id " + id)
                    }
                } else if (xmlDef['ResourcePrototype'].size() == 1) {
                    typeBased = true
                    def name = xmlDef['ResourcePrototype'][0].'@name'
                    resource = resourceHelper.findResourcePrototype(name)
                    if (!resource) {
                        failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                                   "Cannot find resource type " +
                                                   name + " for definition " +
                                                   xmlDef.'@name')
                    }
                } else {
                    failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                               "A single Resource or " +
                                               "ResourcePrototype is required for " +
                                               xmlDef.'@name')
                }
            }

            // Required attributes, basically everything but description
            ['controlFiltered', 'notifyFiltered', 'willRecover', 'range', 'count',
             'frequency', 'enabled', 'active', 'priority',
             'name'].each { attr ->
                if (xmlDef."@${attr}" == null) {
                    failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                              "Required attribute " + attr +
                                              " not found for definition " +
                                              xmlDef.'@name')
                }
            }

            // At least one condition is always required
            if (!xmlDef['AlertCondition'] || xmlDef['AlertCondition'].size() < 1) {
                failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                           "At least 1 AlertCondition is " +
                                           "required for definition " +
                                           xmlDef.'@name')
            }

            // Configure any escalations
            def escalation = null
            if (xmlDef['Escalation'].size() == 1) {

                def xmlEscalation = xmlDef['Escalation'][0]
                def escName = xmlEscalation.'@name'
                if (escName) {
                    escalation = escalationHelper.getEscalation(null, escName)
                }

                if (!escalation) {
                    failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                               "Unable to find escalation with " +
                                               "name '" + escName + "'")
                }
            }

            // Alert priority must be 1-3
            int priority = xmlDef.'@priority'.toInteger()
            if (priority < 1 || priority > 3) {
                failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                           "AlertDefinition priority must be " +
                                           "between 1 (low) and 3 (high) " +
                                           "found=" + priority)
            }

            // Alert frequency must be 0-4
            int frequency = xmlDef.'@frequency'.toInteger()
            if (frequency < 0 || frequency > 4) {
                failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                           "AlertDefinition frequency must be " +
                                           "between 0 and 4 " +
                                           "found=" + frequency)
            }

            // Error with AlertDefinition attributes
            if (failureXml) {
                renderXml() {
                    AlertDefinitionsResponse() {
                        out << failureXml
                    }
                }
                return
            }

            def aeid;
            if (typeBased) {
                aeid = new AppdefEntityTypeID(resource.appdefType,
                                              resource.instanceId)
            } else {
                aeid = resource.entityId
            }

            AlertDefinitionValue adv = new AlertDefinitionValue();
            adv.id          = existing?.id
            adv.name        = xmlDef.'@name'
            adv.description = xmlDef.'@description'
            adv.appdefType  = aeid.type
            adv.appdefId    = aeid.id
            adv.priority    = xmlDef.'@priority'?.toInteger()
            adv.enabled     = xmlDef.'@enabled'.toBoolean()
            adv.active      = xmlDef.'@active'.toBoolean()
            adv.willRecover = xmlDef.'@willRecover'.toBoolean()
            adv.notifyFiltered = xmlDef.'@notifyFiltered'
            adv.frequencyType  = xmlDef.'@frequency'.toInteger()
            adv.count = xmlDef.'@count'.toLong()
            adv.range = xmlDef.'@range'.toLong()
            adv.escalationId = escalation?.id

            def templs
            if (typeBased) {
                def args = [:]
                args.all = 'templates'
                args.resourceType = resource.name
                templs = metricHelper.find(args)
            } else {
                // TODO: This gets all metrics, should warn if that metric is disabled?
                templs = resource.metrics
            }

            for (xmlCond in xmlDef['AlertCondition']) {
                AlertConditionValue acv = new AlertConditionValue()
                def acError

                acError = checkRequiredAttributes(adv.name, xmlCond,
                                                  ['required','type'])
                if (acError != null) {
                    failureXml = acError
                    break
                }

                acv.required = xmlCond.'@required'.toBoolean()
                acv.type = xmlCond.'@type'.toInteger()

                switch (acv.type) {
                    case EventConstants.TYPE_THRESHOLD:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['thresholdMetric',
                                                           'thresholdComparator',
                                                           'thresholdValue'])
                        if (acError != null) {
                            failureXml = acError
                            break
                        }

                        acv.name = xmlCond.'@thresholdMetric'
                        def template = templs.find {
                            acv.name == (typeBased ? it.name : it.template.name)
                        }
                        if (!template) {
                            failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                                       "Unable to find metric " +
                                                       acv.name + " for " +
                                                       resource.name)
                            break
                        }

                        acv.measurementId = template.id
                        acv.comparator    = xmlCond.'@thresholdComparator'
                        acv.threshold     = Double.valueOf(xmlCond.'@thresholdValue')
                        break
                    case EventConstants.TYPE_BASELINE:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['baselineMetric',
                                                           'baselineComparator',
                                                           'baselinePercentage',
                                                           'baselineType'])
                        if (acError != null) {
                            failureXml = acError
                            break
                        }

                        acv.name = xmlCond.'@baselineMetric'
                        def template = templs.find {
                            acv.name == (typeBased ? it.name : it.template.name)
                        }
                        if (!template) {
                            failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                                       "Unable to find metric " +
                                                       acv.name + " for " +
                                                       resource.name)
                            break
                        }

                        acv.measurementId = template.id
                        acv.comparator    = xmlCond.'@baselineComparator'
                        acv.threshold     = Double.valueOf(xmlCond.'@baselinePercentage')
                        acv.option        = xmlCond.'@baselineType'
                        break
                    case EventConstants.TYPE_CONTROL:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['controlAction',
                                                           'controlStatus'])
                        if (acError != null) {
                            failureXml = acError
                            break
                        }
                        acv.name   = xmlCond.'@controlAction'
                        acv.option = xmlCond.'@controlStatus'
                        break
                    case EventConstants.TYPE_CHANGE:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['metricChange'])
                        if (acError != null) {
                            faiureXml = acError
                            break
                        }

                        acv.name = xmlCond.'@metricChange'
                        def template = templs.find {
                            acv.name == (typeBased ? it.name : it.template.name)
                        }
                        if (!template) {
                            failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                                       "Unable to find metric " +
                                                       acv.name + " for " +
                                                       resource.name)
                            break
                        }
                        acv.measurementId = template.id
                        break
                    case EventConstants.TYPE_ALERT:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['recover'])
                        if (acError != null) {
                            failureXml = acError
                            break
                        }

                        // TODO: This requires both recovery alerts to be in the sync. (And ordered)
                        def recoveryDef = definitionsByName[xmlCond.'@recover']
                        if (recoveryDef) {
                            if (aeid.type == recoveryDef.appdefType &&
                                aeid.id == recoveryDef.appdefId) {
                                acv.measurementId = recoveryDef.id
                            }
                        }

                        if (acv.measurementId == null) {
                            failureXml = getFailureXML(ErrorCode.OBJECT_NOT_FOUND,
                                                       "Unable to find recovery " +
                                                       " with name " +
                                                       xmlCond.'@recover')
                            break
                        }

                        break
                    case EventConstants.TYPE_CUST_PROP:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['property'])
                        if (acError != null) {
                            failureXml = acError
                            break
                        }
                        acv.name = xmlCond.'@property'
                        break
                    case EventConstants.TYPE_LOG:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['logLevel',
                                                           'logMatches'])
                        if (acError != null) {
                            failureXml = acError
                            break
                        }


                        def level = EVENT_LEVEL_TO_NUM[xmlCond.'@logLevel']
                        if (level == null) {
                            failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                                       "Unknown log level " +
                                                       xmlCond.'@logLevel')
                            break
                        }

                        acv.name = level.toString()
                        acv.option = xmlDef.'@logMatches'
                        break
                    case EventConstants.TYPE_CFG_CHG:
                        acError = checkRequiredAttributes(adv.name, xmlCond,
                                                          ['configMatch'])
                        if (acError != null) {
                            failureXml = acError
                            break
                        }

                        acv.name = xmlCond.'@configMatch'
                        break
                    default:
                        failureXml = getFailureXML(ErrorCode.INVALID_PARAMETERS,
                                                   "Unhandled AlertCondition " +
                                                   "type " + acv.type + " for " +
                                                   adv.name)
                }

                // Error with AlertCondition
                if (failureXml) {
                    renderXml() {
                        AlertDefinitionsResponse() {
                            out << failureXml
                        }
                    }
                    return
                }
                adv.addCondition(acv)
            }

            // TODO: Migrate this to AlertHelper
            try {
                def sessionId = SessionManager.instance.put(user)
                if (adv.id == null) {
                    def newDef
                    if (typeBased) {
                        newDef =
                            eventBoss.createResourceTypeAlertDefinition(sessionId,
                                                                        aeid, adv)
                    } else {
                        newDef = eventBoss.createAlertDefinition(sessionId,
                                                                     adv)
                    }
                    adv.id = newDef.id
                } else {
                    eventBoss.updateAlertDefinition(sessionId, adv)
                }
            } catch (Exception e) {
                log.error("Error updating alert definition", e)
                failureXml = getFailureXML(ErrorCode.UNEXPECTED_ERROR,
                                           e.getMessage())
            }

            // Error with save/update
            if (failureXml) {
                renderXml() {
                    AlertDefinitionsResponse() {
                        out << failureXml
                    }
                }
                return
            }

            // Keep defs around so we don't need to look up recovery alerts
            def pojo = alertHelper.getById(adv.id)
            definitionsByName[pojo.name] = pojo
        }

        renderXml() {
            out << AlertDefinitionsResponse() {
                out << getSuccessXML()
                for (alertdef in definitionsByName.values()) {
                    out << getAlertDefinitionXML(alertdef, false)
                }
            }
        }
    }
}