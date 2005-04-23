/**
 * ===================================================================
 *
 * Copyright (c) 2003 Ludovic Dubost, All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details, published at
 * http://www.gnu.org/copyleft/gpl.html or in gpl.txt in the
 * root folder of this distribution.
 *
 * Created by
 * User: Ludovic Dubost
 * Date: 24 nov. 2003
 * Time: 01:00:44
 */

package com.xpn.xwiki.store;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.monitor.api.MonitorPlugin;
import com.xpn.xwiki.stats.impl.XWikiStats;
import com.xpn.xwiki.doc.*;
import com.xpn.xwiki.objects.*;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.web.XWikiRequest;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.Dialect;
import org.hibernate.impl.SessionFactoryImpl;
import org.hibernate.impl.SessionImpl;
import org.hibernate.tool.hbm2ddl.DatabaseMetadata;
import org.apache.commons.jrcs.rcs.Archive;
import org.apache.commons.jrcs.rcs.Node;
import org.apache.commons.jrcs.rcs.Version;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.HibernateException;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.io.BufferedReader;
import java.io.File;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;


public class XWikiHibernateStore extends XWikiDefaultStore {

    private static final Log log = LogFactory.getLog(XWikiHibernateStore.class);

    private Map connections = new HashMap();
    private int nbConnections = 0;
    private SessionFactory sessionFactory;
    private Configuration configuration;

    private String hibpath;
    private URL hiburl;

    public XWikiHibernateStore(XWiki xwiki, XWikiContext context) {
        String path = xwiki.Param("xwiki.store.hibernate.path");
        if (context.getEngineContext()==null)
            setPath(path);
        else {
            try {
                setHibUrl(context.getEngineContext().getResource(path));
            } catch (MalformedURLException e) {
            }
        }
    }

    public XWikiHibernateStore(String hibpath) {
        setPath(hibpath);
    }


    public String getPath() {
        return hibpath;
    }

    public void setPath(String hibpath) {
        this.hibpath = hibpath;
    }

    public URL getHibUrl() {
        return hiburl;
    }

    public void setHibUrl(URL hiburl) {
        this.hiburl = hiburl;
    }

    // Helper Methods
    private void initHibernate() throws HibernateException {
        // Load Configuration and build SessionFactory
        String path = getPath();
        if (path!=null)
            setConfiguration((new Configuration()).configure(new File(path)));
        else {
            URL hiburl = getHibUrl();
            if (hiburl!=null)
                setConfiguration(new Configuration().configure(hiburl));
            else
                setConfiguration(new Configuration().configure());
        }

        setSessionFactory(getConfiguration().buildSessionFactory());
    }

    public Session getSession(XWikiContext context) {
        Session session = (Session) context.get("hibsession");
        return session;
    }

    public void setSession(Session session, XWikiContext context) {
        if (session==null)
            context.remove("hibsession");
        else
            context.put("hibsession", session);
    }


    public Transaction getTransaction(XWikiContext context) {
        Transaction transaction = (Transaction) context.get("hibtransaction");
        return transaction;
    }

    public void setTransaction(Transaction transaction, XWikiContext context) {
        if (transaction==null)
            context.remove("hibtransaction");
        else
            context.put("hibtransaction", transaction);
    }


    public void shutdownHibernate(XWikiContext context) throws HibernateException {
        closeSession(getSession(context));
        if (getSessionFactory()!=null) {
            ((SessionFactoryImpl)getSessionFactory()).getConnectionProvider().close();
        }
    }

    public void updateSchema(XWikiContext context) throws HibernateException {
        Session session;
        Connection connection;
        DatabaseMetadata meta;
        Statement stmt=null;
        Dialect dialect = Dialect.getDialect(getConfiguration().getProperties());
        boolean bTransaction = true;

        try {
            try {
                bTransaction = beginTransaction(context);
                session = getSession(context);
                connection = session.connection();
                setDatabase(session, context);

                meta = new DatabaseMetadata(connection, dialect);
                stmt = connection.createStatement();
            }
            catch (SQLException sqle) {
                if ( log.isErrorEnabled() ) log.error("Failed updating schema: " + sqle.getMessage());
                throw sqle;
            }

            String[] createSQL = configuration.generateSchemaUpdateScript(dialect, meta);
            MonitorPlugin monitor  = getMonitorPlugin(context);
            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("sqlupgrade");
            try {
                for (int j = 0; j < createSQL.length; j++) {
                    final String sql = createSQL[j];
                    if ( log.isDebugEnabled() ) log.debug("Update Schema sql: " + sql);
                    stmt.executeUpdate(sql);
                }
                connection.commit();
            }
            catch (SQLException e) {
                connection.rollback();
                if ( log.isErrorEnabled() ) log.error("Failed updating schema: " + e.getMessage());
            }
            finally {
                // End monitoring timer
                if (monitor!=null)
                    monitor.endTimer("sqlupgrade");
            }
                
            // Make sure we have no null valued in integer fields
            stmt.executeUpdate("update xwikidoc set xwd_translation=0 where xwd_translation is null");
            stmt.executeUpdate("update xwikidoc set xwd_language='' where xwd_language is null");
            stmt.executeUpdate("update xwikidoc set xwd_default_language='' where xwd_default_language is null");
            String fullNameSQL;
            if (context.getWiki().isMySQL())
                fullNameSQL = "CONCAT(xwd_web,'.',xwd_name)";
            else
                fullNameSQL = "xwd_web||'.'||xwd_name";
            stmt.executeUpdate("update xwikidoc set xwd_fullname=" + fullNameSQL + " where xwd_fullname is null");
            connection.commit();
        }
        catch (Exception e) {
            if ( log.isErrorEnabled() ) log.error("Failed updating schema: " + e.getMessage());
        }
        finally {

            try {
                if (stmt!=null) stmt.close();
                if (bTransaction)
                    endTransaction(context, true);
            }
            catch (Exception e) {
            }
        }
    }


    public void checkHibernate(XWikiContext context) throws HibernateException {

        if (getSessionFactory()==null) {
            initHibernate();

            /* Check Schema */
            if (getSessionFactory()!=null) {
                updateSchema(context);
            }
        }
    }

    protected boolean isVirtual(XWikiContext context) {
        if ((context==null)||(context.getWiki()==null))
            return true;
        return context.getWiki().isVirtual();
    }

    public void setDatabase(Session session, XWikiContext context) throws XWikiException {
        if (isVirtual(context)) {
            String database = context.getDatabase();
            try {
                if ( log.isDebugEnabled() ) log.debug("Switch database to: " + database);
                if (database!=null)
                    session.connection().setCatalog(database);
            } catch (Exception e) {
                Object[] args = { database };
                throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SWITCH_DATABASE,
                        "Exception while switching to database {0}", e, args);
            }
        }
    }

    public boolean beginTransaction(XWikiContext context)
            throws HibernateException, XWikiException {

        Transaction transaction = getTransaction(context);
        Session session = getSession(context);

        if (((session==null)&&(transaction!=null))
                ||((transaction==null)&&(session!=null))) {
            if ( log.isWarnEnabled() ) log.warn("Incompatible session (" + session + ") and transaction (" + transaction + ") status");
            return false;
        }

        if (transaction!=null) {
            if ( log.isDebugEnabled() ) log.debug("Taking session from context " + session);
            if ( log.isDebugEnabled() ) log.debug("Taking transaction from context " + transaction);
            return false;
        }
        if (session==null) {
            if ( log.isDebugEnabled() ) log.debug("Trying to get session from pool");
            session = (SessionImpl)getSessionFactory().openSession();
            if ( log.isDebugEnabled() ) log.debug("Taken session from pool " + session);

            // Keep some statistics about session and connections
            nbConnections++;
            addConnection(session.connection(), context);

            setSession(session, context);
            setDatabase(session, context);

            if ( log.isDebugEnabled() ) log.debug("Trying to open transaction");
            transaction = session.beginTransaction();
            if ( log.isDebugEnabled() ) log.debug("Opened transaction " + transaction);
            setTransaction(transaction, context);
        }
        return true;
    }

    private void addConnection(Connection connection, XWikiContext context) {
        if (connection!=null)
            connections.put(connection, new ConnectionMonitor(connection, context));
    }

    private void removeConnection(Connection connection) {
        try {
            if (connection!=null)
                connections.remove(connection);
        } catch (Exception e) {
        }
    }

    public void endTransaction(XWikiContext context, boolean commit)
            throws HibernateException {
        Session session = null;
        try {
            session = getSession(context);
            Transaction transaction = getTransaction(context);
            setSession(null, context);
            setTransaction(null, context);

            if (transaction!=null) {
                if ( log.isDebugEnabled() ) log.debug("Releasing hibernate transaction " + transaction);
                if (commit) {
                    transaction.commit();
                } else {
                    // Don't commit the transaction, can be faster for read-only operations
                    transaction.rollback();
                }
            }
        } finally {
            if (session!=null)
                closeSession(session);
        }
    }

    private void closeSession(Session session) throws HibernateException {
        if (session!=null) {
            try {
                if ( log.isDebugEnabled() ) log.debug("Releasing hibernate session " + session);
                Connection connection = session.connection();
                if ((connection!=null)) {
                    nbConnections--;
                    try {
                        removeConnection(connection);
                    } catch (Throwable e) {
                        // This should not happen
                        e.printStackTrace();
                    }
                }
            } finally {
                session.close();
            }
        }
    }

    public void cleanUp(XWikiContext context) {
        try {
            Session session = getSession(context);
            if (session!=null) {
                if ( log.isWarnEnabled() ) log.warn("Cleanup of session was needed: " + session);
                endTransaction(context, false);
            }
        } catch (HibernateException e) {
        }
    }

    public void createWiki(String wikiName, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        String database = context.getDatabase();
        Statement stmt = null;
        try {
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            Connection connection = session.connection();
            stmt = connection.createStatement();
            stmt.execute("create database " + wikiName);
            endTransaction(context, true);
        }
        catch (Exception e) {
            Object[] args = { wikiName  };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CREATE_DATABASE,
                    "Exception while create wiki database {0}", e, args);
        } finally {
            context.setDatabase(database);
            try {
                if (stmt!=null)
                    stmt.close();
            } catch (Exception e) {}
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

public boolean exists(XWikiDocument doc, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        MonitorPlugin monitor  = getMonitorPlugin(context);
        try {

            doc.setStore(this);
            checkHibernate(context);

            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate");

            bTransaction = bTransaction && beginTransaction(context);
            Session session = getSession(context);
            String fullName = doc.getFullName();

            String sql = "select doc.fullName from XWikiDocument as doc where doc.fullName=:fullName";
            if (monitor!=null)
              monitor.setTimerDesc("hibernate", sql);
            Query query = session.createQuery(sql);
            query.setString("fullName", fullName);
            Iterator it = query.list().iterator();
            while (it.hasNext()) {
                if (fullName.equals(it.next()))
                    return true;
            }
            return false;
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CHECK_EXISTS_DOC,
                    "Exception while reading document {0}", e, args);
        } finally {
            // End monitoring timer
            if (monitor!=null)
             monitor.endTimer("hibernate");

            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void saveXWikiDoc(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException {
        MonitorPlugin monitor  = getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate");
            doc.setStore(this);

            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            saveAttachmentList(doc, context, false);


            // Handle the latest text file
            if (doc.isContentDirty()||doc.isMetaDataDirty()) {
                doc.setDate(new Date());
                doc.incrementVersion();
                doc.updateArchive(doc.toXML(context));
            } else {
                // Make sure the getArchive call has been made once
                // with a valid context
                try {
                    doc.getArchive(context);
                } catch (XWikiException e) {
                    // this is a non critical error
                }
            }

            // Verify if the document already exists
            Query query = session.createQuery("select xwikidoc.id from XWikiDocument as xwikidoc where xwikidoc.id = :id");
            query.setLong("id", doc.getId());
            if (query.uniqueResult()==null)
                session.save(doc);
            else
                session.update(doc);

            // Remove properties planned for removal
            if (doc.getObjectsToRemove().size()>0) {
                for (int i=0;i<doc.getObjectsToRemove().size();i++) {
                    deleteXWikiObject((BaseObject)doc.getObjectsToRemove().get(i), context, false);
                }
                doc.setObjectsToRemove(new ArrayList());
            }

            BaseClass bclass = doc.getxWikiClass();
            if (bclass!=null) {
                bclass.setName(doc.getFullName());
                if (bclass.getFieldList().size()>0)
                    saveXWikiClass(bclass, context, false);
            } else {
                // TODO: Remove existing class
            }

            // TODO: Delete all objects for which we don't have a name in the Map..

            Iterator it = doc.getxWikiObjects().values().iterator();
            while (it.hasNext()) {
                Vector objects = (Vector) it.next();
                for (int i=0;i<objects.size();i++) {
                    BaseCollection obj = (BaseCollection)objects.get(i);
                    if (obj!=null)
                        saveXWikiCollection(obj, context, false);
                }
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
            doc.setNew(false);
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC,
                    "Exception while saving document {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }

    }

    public void saveXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException {
        saveXWikiDoc(doc, context, true);
    }

    public XWikiDocument loadXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException {
        //To change body of implemented methods use Options | File Templates.
        BufferedReader fr = null;
        boolean bTransaction = true;
        MonitorPlugin monitor = getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate");
            doc.setStore(this);
            checkHibernate(context);
            bTransaction = bTransaction && beginTransaction(context);
            Session session = getSession(context);

            try {
                session.load(doc, new Long(doc.getId()));
                doc.setNew(false);
            } catch (ObjectNotFoundException e)
            { // No document
                doc.setNew(true);
                return doc;
            }

            // Loading the attachment list
            loadAttachmentList(doc, context, false);

            // TODO: handle the case where there are no xWikiClass and xWikiObject in the Database
            BaseClass bclass = new BaseClass();
            bclass.setName(doc.getFullName());
            loadXWikiClass(bclass, context, false);
            doc.setxWikiClass(bclass);

            Query query;
            query = session.createQuery("from BaseObject as bobject where bobject.name = :name order by bobject.number");
            query.setText("name", doc.getFullName());
            Iterator it = query.list().iterator();

            boolean hasGroups = false;
            while (it.hasNext()) {
                BaseObject object = (BaseObject) it.next();
                String className = object.getClassName();
                // We use the internal class to store the statistics
                if (className.equals("internal"))
                    continue;

                if (className.equals("XWiki.XWikiGroups")) {
                    hasGroups = true;
                    continue;
                }

                // It seems to search before is case insensitive
                // And this would break the loading if we get an
                // object which doesn't really belong to this document
                if (!object.getName().equals(doc.getFullName()))
                    continue;

                if (!className.equals("")) {
                    loadXWikiCollection(object, context, false, true);
                    doc.setObject(className, object.getNumber(), object);
                }
            }

            if (hasGroups) {
                Query query2;
                query2 = session.createQuery("select bobject.number, prop.value from StringProperty as prop, BaseObject as bobject where bobject.name = :name and bobject.className='XWiki.XWikiGroups' and bobject.id=prop.id.id and prop.id.name='member' order by bobject.number");
                query2.setText("name", doc.getFullName());
                Iterator it2 = query2.list().iterator();
                while (it2.hasNext()) {
                    Object[] result = (Object[])it2.next();
                    Integer number = (Integer)result[0];
                    String member = (String)result[1];
                    BaseObject obj = new BaseObject();
                    obj.setName(doc.getFullName());
                    obj.setClassName("XWiki.XWikiGroups");
                    obj.setNumber(number.intValue());
                    obj.setStringValue("member", member);
                    doc.setObject("XWiki.XWikiGroups", obj.getNumber(), obj);
                }


            }

            if (bTransaction)
                endTransaction(context, false);
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_READING_DOC,
                    "Exception while reading document {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
        return doc;
    }

    private MonitorPlugin getMonitorPlugin(XWikiContext context) {
        try {
        if ((context==null)||(context.getWiki()==null))
            return null;

        return (MonitorPlugin) context.getWiki().getPlugin("monitor", context);
        } catch (Exception e) {
            return null;
        }
    }

    public XWikiDocument loadXWikiDoc(XWikiDocument basedoc,String version, XWikiContext context) throws XWikiException {
        XWikiDocument doc = new XWikiDocument(basedoc.getWeb(), basedoc.getName());
        MonitorPlugin monitor = getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate");
            doc.setStore(this);
            Archive archive = basedoc.getRCSArchive();
            doc.setRCSArchive(archive);

            if (archive == null) {
                doc.updateArchive(doc.toXML(context));
                archive = basedoc.getRCSArchive();
            }

            Object[] text = (Object[]) archive.getRevision(version);
            if (text[0].toString().startsWith("<")) {
                StringBuffer content = new StringBuffer();
                for (int i=0;i<text.length;i++) {
                    String line = text[i].toString();
                    content.append(line);
                    content.append("\n");
                }
                doc.fromXML(content.toString());
            } else {
                StringBuffer content = new StringBuffer();
                boolean bMetaDataDone = false;
                for (int i=0;i<text.length;i++) {
                    String line = text[i].toString();
                    if (bMetaDataDone||(XWikiRCSFileStore.parseMetaData(doc,line)==false)) {
                        content.append(line);
                        content.append("\n");
                    }
                    doc.setContent(content.toString());
                }
            }
            // Make sure the document has the same name
            // as the new document (in case there was a name change
            doc.setName(basedoc.getName());
            doc.setWeb(basedoc.getWeb());
        } catch (Exception e) {
            Object[] args = { doc.getFullName(), version.toString() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_READING_VERSION,
                    "Exception while reading document {0} version {1}", e, args);
        } finally {
            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
        return doc;
    }


    public void deleteXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        MonitorPlugin monitor  = getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate");
            checkHibernate(context);
            bTransaction = beginTransaction(context);
            Session session = getSession(context);

            if (doc.getStore()==null) {
                Object[] args = { doc.getFullName() };
                throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CANNOT_DELETE_UNLOADED_DOC,
                        "Impossible to delete document {0} if it is not loaded", null, args);
            }

            // Let's delete any attachment this document might have
            List attachlist = doc.getAttachmentList();
            for (int i=0;i<attachlist.size();i++) {
                XWikiAttachment attachment = (XWikiAttachment) attachlist.get(i);
                deleteXWikiAttachment(attachment, false, context, false);
            }

            BaseClass bclass = doc.getxWikiClass();
            if ((bclass==null)&&(bclass.getName()!=null)) {
                deleteXWikiClass(bclass, context, false);
            }

            // Find the list of classes for which we have an object
            // Remove properties planned for removal
            if (doc.getObjectsToRemove().size()>0) {
                for (int i=0;i<doc.getObjectsToRemove().size();i++) {
                    deleteXWikiObject((BaseObject)doc.getObjectsToRemove().get(i), context, false);
                }
                doc.setObjectsToRemove(new ArrayList());
            }
            Iterator it = doc.getxWikiObjects().values().iterator();
            while (it.hasNext()) {
                Vector objects = (Vector) it.next();
                for (int i=0;i<objects.size();i++) {
                    BaseObject obj = (BaseObject)objects.get(i);
                    if (obj!=null)
                        deleteXWikiObject(obj, context, false);
                }
            }

            session.delete(doc);
            if (bTransaction)
                endTransaction(context, true);
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_DOC,
                    "Exception while deleting document {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public Version[] getXWikiDocVersions(XWikiDocument doc) throws XWikiException {
        return getXWikiDocVersions(doc, null);
    }

    public Version[] getXWikiDocVersions(XWikiDocument doc, XWikiContext context) throws XWikiException {
        try {
            if (doc.getStore()==null) {
                doc = loadXWikiDoc(doc, context);
            }

            if (doc.getRCSArchive()==null)
                return new Version[0];

            Node[] nodes = doc.getRCSArchive().changeLog();
            Version[] versions = new Version[nodes.length];
            for (int i=0;i<nodes.length;i++) {
                versions[i] = nodes[i].getVersion();
            }
            return versions;
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_READING_REVISIONS,
                    "Exception while reading document {0} revisions", e, args);
        }
    }


    public void saveXWikiObject(BaseObject object, XWikiContext context, boolean bTransaction) throws XWikiException {
        saveXWikiCollection(object, context, bTransaction);
    }

    public void saveXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (object==null)
                return;

            // We need a slightly different behavior here
            boolean stats = (object instanceof XWikiStats);

            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // Verify if the property already exists
            Query query;
            if (stats)
                query = session.createQuery("select obj.id from " +
                            object.getClass().getName() + " as obj where obj.id = :id");
            else
                query = session.createQuery("select obj.id from BaseObject as obj where obj.id = :id");
            query.setInteger("id", object.getId());
            if (query.uniqueResult()==null)
                session.save(object);
            else
                session.update(object);

            if (!object.getClassName().equals("internal")) {
                // Remove all existing properties
                if (object.getFieldsToRemove().size()>0) {
                    for (int i=0;i<object.getFieldsToRemove().size();i++) {
                        session.delete(object.getFieldsToRemove().get(i));
                    }
                    object.setFieldsToRemove(new ArrayList());
                }

                Iterator it = object.getPropertyList().iterator();
                while (it.hasNext()) {
                    String key = (String) it.next();
                    BaseProperty prop = (BaseProperty) object.getField(key);
                    if (!prop.getName().equals(key)) {
                        Object[] args = { key, object.getName() };
                        throw new XWikiException(XWikiException.MODULE_XWIKI_CLASSES, XWikiException.ERROR_XWIKI_CLASSES_FIELD_INVALID,
                                "Field {0} in object {1} has an invalid name", null, args);
                    }
                    saveXWikiProperty(prop, context, false);
                }
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (XWikiException xe) {
            throw xe;
        } catch (Exception e) {
            Object[] args = { object.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_OBJECT,
                    "Exception while saving object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, true);
            } catch (Exception e) {}
        }
    }

    public void loadXWikiObject(BaseObject object, XWikiContext context, boolean bTransaction) throws XWikiException {
        loadXWikiCollection(object, context, bTransaction, false);
    }

    public void loadXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction) throws XWikiException {
        loadXWikiCollection(object, context, bTransaction, false);
    }

    public void loadXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction, boolean alreadyLoaded) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            if (!alreadyLoaded) {
             try {
                session.load(object, new Integer(object.getId()));
             }
             catch (ObjectNotFoundException e) {
                // There is no object data saved
                object = null;
                return;
             }
            }

            String className = object.getClassName();
            if (!className.equals("internal")) {
                HashMap map = new HashMap();
                Query query = session.createQuery("select prop.name, prop.classType from BaseProperty as prop where prop.id.id = :id");
                query.setInteger("id", object.getId());
                Iterator it = query.list().iterator();
                while (it.hasNext()) {
                    Object obj = it.next();
                    Object[] result = (Object[]) obj;
                    String name = (String)result[0];
                    String classType = (String)result[1];
                    BaseProperty property = (BaseProperty) Class.forName(classType).newInstance();
                    property.setObject(object);
                    property.setName(name);
                    loadXWikiProperty(property, context, false);
                    object.addField(name, property);
                }
            }
            if (bTransaction) {
                endTransaction(context, false);
            }
        } catch (Exception e) {
            Object[] args = { object.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                    "Exception while loading object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }

    }

    public void deleteXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction) throws XWikiException {
        deleteXWikiCollection(object, context, bTransaction, false);
    }

    public void deleteXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction, boolean evict) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            if (!object.getClassName().equals("internal")) {
                for (Iterator it = object.getFieldList().iterator(); it.hasNext();) {
                    BaseProperty property = (BaseProperty)it.next();
                    session.delete(property);
                    if (evict)
                        session.evict(property);
                }
            }
            session.delete(object);
            if (evict)
                session.evict(object);
            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = { object.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_OBJECT,
                    "Exception while deleting object {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void deleteXWikiObject(BaseObject baseObject, XWikiContext context, boolean bTransaction, boolean bEvict) throws XWikiException {
        deleteXWikiCollection(baseObject, context, bTransaction, bEvict);
    }

    public void deleteXWikiObject(BaseObject baseObject, XWikiContext context, boolean b) throws XWikiException {
        deleteXWikiCollection(baseObject, context, b);
    }

    public void deleteXWikiClass(BaseClass baseClass, XWikiContext context, boolean b) throws XWikiException {
        deleteXWikiCollection(baseClass, context, b);
    }


    public void loadXWikiProperty(PropertyInterface property, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            session.load(property, (Serializable) property);

            // TODO: understand why collections are lazy loaded
            // Let's force reading lists if there is a list
            // This seems to be an issue since Hibernate 3.0
            // Without this test ViewEditTest.testUpdateAdvanceObjectProp fails
            if (property instanceof ListProperty) {
                ((ListProperty)property).getList();
            }

            if (bTransaction) {
                endTransaction(context, false);
            }
        }
        catch (Exception e) {
            BaseCollection obj = property.getObject();
            Object[] args = { (obj!=null) ? obj.getName() : "unknown", property.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                    "Exception while saving property {1} of object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }


    public void saveXWikiProperty(PropertyInterface property, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

// I'm using a local transaction
// There might be implications to this for a wider transaction
            Transaction ltransaction = session.beginTransaction();

// Use to chose what to delete
            boolean isSave = false;
            try
            {
                Query query = session.createQuery("select prop.name from BaseProperty as prop where prop.id.id = :id and prop.id.name= :name");
                query.setInteger("id", property.getId());
                query.setString("name", property.getName());
                if (query.uniqueResult()==null) {
                    isSave = true;
                    session.save(property);
                }
                else {
                    isSave = false;
                    session.update(property);
                }
                session.flush();
                ltransaction.commit();
            } catch (Exception e) {
// We can't clean-up ListProperties
                if (property instanceof ListProperty)
                    throw e;

// This seems to have failed..
// This is an attempt to cleanup a potential mess
// This code is only called if the tables are in an incoherent state
// (Example: data in xwikiproperties and no data in xwikiintegers or vice-versa)
// TODO: verify of the code works with longer transactions
                BaseProperty prop2;
// Depending on save/update there is too much data either
// in the BaseProperty table or in the inheritated property table
// We need to delete this data
                if (isSave)
                    prop2 = (BaseProperty) property;
                else
                    prop2 = new BaseProperty();

                prop2.setName(property.getName());
                prop2.setObject(property.getObject());
                ltransaction.rollback();

// We need to run the delete in a separate session
// This is not a problem since this is cleaning up
                Session session2 = getSessionFactory().openSession();
                Transaction transaction2 = session2.beginTransaction();
                session2.delete(prop2);
                session2.flush();

// I don't understand why I can't run this in the general session
// This might make transactions fail
                if (!isSave)
                    session2.save(property);
                transaction2.commit();
                session2.close();
            }

            if (bTransaction)
                endTransaction(context, true);

        }
        catch (Exception e) {
            BaseCollection obj = property.getObject();
            Object[] args = { (obj!=null) ? obj.getName() : "unknown", property.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                    "Exception while saving property {1} of object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void saveXWikiClass(BaseClass bclass, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {

            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


// Verify if the property already exists
            Query query = session.createQuery("select obj.id from BaseClass as obj where obj.id = :id");
            query.setInteger("id", bclass.getId());
            if (query.uniqueResult()==null)
                session.save(bclass);
            else
                session.update(bclass);

            // Remove all existing properties
            if (bclass.getFieldsToRemove().size()>0) {
                for (int i=0;i<bclass.getFieldsToRemove().size();i++) {
                    session.delete(bclass.getFieldsToRemove().get(i));
                }
                bclass.setFieldsToRemove(new ArrayList());
            }


            Collection coll = bclass.getFieldList();
            Iterator it = coll.iterator();
            while (it.hasNext()) {
                PropertyClass prop = (PropertyClass) it.next();
                saveXWikiClassProperty(prop, context, false);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = { bclass.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_CLASS,
                    "Exception while saving class {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }


    public void loadXWikiClass(BaseClass bclass, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            try {
                session.load(bclass, new Integer(bclass.getId()));
            }
            catch (ObjectNotFoundException e) {
// There is no class data saved
                bclass = null;
                return;
            }
            HashMap map = new HashMap();
            Query query = session.createQuery("select prop.name, prop.classType from PropertyClass as prop where prop.id.id = :id order by prop.number asc");
            query.setInteger("id", bclass.getId());
            Iterator it = query.list().iterator();
            while (it.hasNext()) {
                Object obj = it.next();
                Object[] result = (Object[]) obj;
                String name = (String)result[0];
                String classType = (String)result[1];
                PropertyClass property = (PropertyClass) Class.forName(classType).newInstance();
                property.setName(name);
                property.setObject(bclass);
                session.load(property, property);
                bclass.addField(name, property);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = { bclass.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_CLASS,
                    "Exception while loading class {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void saveXWikiClassProperty(PropertyClass property, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


// I'm using a local transaction
// There might be implications to this for a wider transaction
            Transaction ltransaction = session.beginTransaction();

// Use to chose what to delete
            boolean isSave = false;
            try
            {
                Query query = session.createQuery("select prop.name from PropertyClass as prop where prop.id.id = :id and prop.id.name= :name");
                query.setInteger("id", property.getId());
                query.setString("name", property.getName());
                if (query.uniqueResult()==null) {
                    isSave = true;
                    session.save(property);
                }
                else {
                    isSave = false;
                    session.update(property);
                }

                session.flush();
                ltransaction.commit();
            } catch (Exception e) {
// This seems to have failed..
// This is an attempt to cleanup a potential mess
// This code is only called if the tables are in an incoherent state
// (Example: data in xwikiproperties and no data in xwikiintegers or vice-versa)
// TODO: verify of the code works with longer transactions
                PropertyClass prop2;
// Depending on save/update there is too much data either
// in the BaseProperty table or in the inheritated property table
// We need to delete this data
                if (isSave)
                    prop2 = (PropertyClass) property;
                else
                    prop2 = new PropertyClass();

                prop2.setName(property.getName());
                prop2.setObject(property.getObject());
                ltransaction.rollback();

// We need to run the delete in a separate session
// This is not a problem since this is cleaning up
                Session session2 = getSessionFactory().openSession();
                Transaction transaction2 = session2.beginTransaction();
                session2.delete(prop2);
                session2.flush();

// I don't understand why I can't run this in the general session
// This might make transactions fail
                if (!isSave)
                    session2.save(property);
                transaction2.commit();
                session2.close();
            }

            if (bTransaction)
                endTransaction(context, true);

        }
        catch (Exception e) {
            Object[] args = { property.getObject().getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_CLASS,
                    "Exception while saving class {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void loadAttachmentList(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("from XWikiAttachment as attach where attach.docId=:docid");
            query.setLong("docid", doc.getId());
            List list = query.list();
            for (int i=0;i<list.size();i++) {
                ((XWikiAttachment)list.get(i)).setDoc(doc);
            }
            doc.setAttachmentList(list);
            if (bTransaction)
                endTransaction(context, false);
        }
        catch (Exception e) {
            e.printStackTrace();
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCHING_ATTACHMENT,
                    "Exception while searching attachments for documents {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void saveAttachmentList(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            List list = doc.getAttachmentList();
            for (int i=0;i<list.size();i++) {
                XWikiAttachment attachment = (XWikiAttachment) list.get(i);
                saveAttachment(attachment, false, context, false);
            }

            if (bTransaction)
                endTransaction(context, true);
        }
        catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_ATTACHMENT_LIST,
                    "Exception while saving attachments attachment list of document {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void saveAttachment(XWikiAttachment attachment, XWikiContext context, boolean bTransaction) throws XWikiException {
        saveAttachment(attachment, true, context, bTransaction);
    }

    public void saveAttachment(XWikiAttachment attachment, boolean parentUpdate, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            Query query = session.createQuery("select attach.id from XWikiAttachment as attach where attach.id = :id");
            query.setLong("id", attachment.getId());
            if (query.uniqueResult()==null)
                session.save(attachment);
            else
                session.update(attachment);

            if (parentUpdate)
                saveXWikiDoc(attachment.getDoc(), context, false);
            if (bTransaction) {
                endTransaction(context, true);
            }
        }
        catch (Exception e) {
            Object[] args = { attachment.getFilename(), attachment.getDoc().getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_ATTACHMENT,
                    "Exception while saving attachments for attachment {0} of document {1}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }
    public void saveAttachmentContent(XWikiAttachment attachment, XWikiContext context, boolean bTransaction) throws XWikiException {
        saveAttachmentContent(attachment, true, context, bTransaction);
    }

    public void saveAttachmentContent(XWikiAttachment attachment, boolean parentUpdate, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            XWikiAttachmentContent content = attachment.getAttachment_content();
            if (content.isContentDirty()) {
                attachment.updateContentArchive(context);
            }
            XWikiAttachmentArchive archive = attachment.getAttachment_archive();

            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            Query query = session.createQuery("select attach.id from XWikiAttachmentContent as attach where attach.id = :id");
            query.setLong("id", content.getId());
            if (query.uniqueResult()==null)
                session.save(content);
            else
                session.update(content);

            query = session.createQuery("select attach.id from XWikiAttachmentArchive as attach where attach.id = :id");
            query.setLong("id", archive.getId());
            if (query.uniqueResult()==null)
                session.save(archive);
            else
                session.update(archive);

            if (parentUpdate)
                saveXWikiDoc(attachment.getDoc(), context, false);
            if (bTransaction) {
                endTransaction(context, true);
            }
        }
        catch (Exception e) {
            Object[] args = { attachment.getFilename(), attachment.getDoc().getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_ATTACHMENT,
                    "Exception while saving attachment {0} of document {1}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }

    }

    public void loadAttachmentContent(XWikiAttachment attachment, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            XWikiAttachmentContent content = new XWikiAttachmentContent(attachment);
            attachment.setAttachment_content(content);

            session.load(content, new Long(content.getId()));

            if (bTransaction)
                endTransaction(context, false);
        }
        catch (Exception e) {
            Object[] args = { attachment.getFilename(), attachment.getDoc().getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_ATTACHMENT,
                    "Exception while loading attachment {0} of document {1}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void loadAttachmentArchive(XWikiAttachment attachment, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            XWikiAttachmentArchive archive = new XWikiAttachmentArchive();
            archive.setAttachment(attachment);
            attachment.setAttachment_archive(archive);

            session.load(archive, new Long(archive.getId()));

            if (bTransaction)
                endTransaction(context, false);
        }
        catch (Exception e) {
            Object[] args = { attachment.getFilename(), attachment.getDoc().getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_ATTACHMENT,
                    "Exception while loading attachment {0} of document {1}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void deleteXWikiAttachment(XWikiAttachment attachment,  XWikiContext context, boolean bTransaction) throws XWikiException {
        deleteXWikiAttachment(attachment, true, context, bTransaction);
    }

    public XWikiLock loadLock(long docId, XWikiContext context, boolean bTransaction) throws XWikiException {
        XWikiLock lock=null;
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("select lock.docId from XWikiLock as lock where lock.docId = :docId");
            query.setLong("docId", docId);
            if (query.uniqueResult()!=null)
            {
                lock = new XWikiLock();
                session.load(lock, new Long(docId));
            }

            if (bTransaction)
                endTransaction(context, false);
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_LOCK,
                    "Exception while loading lock", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
        return lock;
    }

    public void saveLock(XWikiLock lock, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("select lock.docId from XWikiLock as lock where lock.docId = :docId");
            query.setLong("docId", lock.getDocId());
            if (query.uniqueResult()==null)
                session.save(lock);
            else
                session.update(lock);

            if (bTransaction) {
                endTransaction(context, true);
            }
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_LOCK,
                    "Exception while locking document", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void deleteLock(XWikiLock lock, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            session.delete(lock);

            if (bTransaction)
                endTransaction(context, true);
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_LOCK,
                    "Exception while deleting lock", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void deleteXWikiAttachment(XWikiAttachment attachment, boolean parentUpdate, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }

            Session session = getSession(context);

            // Delete the three attachement entries
            loadAttachmentContent(attachment, context, false);
            session.delete(attachment.getAttachment_content());
            loadAttachmentArchive(attachment, context, false);
            session.delete(attachment.getAttachment_archive());
            session.delete(attachment);

            if (parentUpdate) {
                List list = attachment.getDoc().getAttachmentList();
                for (int i=0;i<list.size();i++) {
                    XWikiAttachment attach = (XWikiAttachment) list.get(i);
                    if (attachment.getFilename().equals(attach.getFilename())) {
                        list.remove(i);
                        break;
                    }
                }
                saveXWikiDoc(attachment.getDoc(), context, false);
            }
            if (bTransaction) {
                endTransaction(context, true);
            }
        }
        catch (Exception e) {
            Object[] args = { attachment.getFilename(), attachment.getDoc().getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_ATTACHMENT,
                    "Exception while deleting attachment {0} of document {1}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }


    public void getContent(XWikiDocument doc, StringBuffer buf) {
        buf.append(doc.getContent());
    }

    public List getClassList(XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        try {
            checkHibernate(context);
            bTransaction = beginTransaction(context);
            Session session = getSession(context);

            Query query = session.createQuery("select bclass.name from BaseClass as bclass");
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                String name = (String)it.next();
                list.add(name);
            }
            if (bTransaction)
                endTransaction(context, false);
            return list;
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching class list", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }


    public List search(String sql, int nb, int start, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;

        if (sql==null)
            return null;

        MonitorPlugin monitor  = getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate");
            checkHibernate(context);
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            Query query = session.createQuery(sql.toString());
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                list.add(it.next());
            }
            if (bTransaction)
                endTransaction(context, false);
            return list;
        }
        catch (Exception e) {
            Object[] args = { sql };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public List search(Query query, int nb, int start, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;

        if (query==null)
            return null;

        MonitorPlugin monitor  = getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate", query.getQueryString());
            checkHibernate(context);
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                list.add(it.next());
            }
            if (bTransaction)
                endTransaction(context, false);
            return list;
        }
        catch (Exception e) {
            Object[] args = { query.toString() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public List searchDocumentsNames(String wheresql, int nb, int start, String selectColumns, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        MonitorPlugin monitor  = getMonitorPlugin(context);
        try {
            StringBuffer sql = new StringBuffer("select distinct doc.web, doc.name");
            if (!selectColumns.trim().equals("")) {
                sql.append(",");
                sql.append(selectColumns);
            }
            sql.append(" from XWikiDocument as doc");
            if (wheresql==null)
                wheresql = "";

            wheresql.trim();
            if (!wheresql.equals("")) {
                if ((!wheresql.startsWith("where"))&&(!wheresql.startsWith(",")))
                    sql.append(" where ");
                else
                    sql.append(" ");

                sql.append(wheresql);
            }
            String ssql = sql.toString();

            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate", ssql);

            checkHibernate(context);
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            Query query = session.createQuery(ssql);
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                Object[] result = (Object[]) it.next();
                String name = (String) result[0] + "." + (String)result[1];
                list.add(name);
            }
            if (bTransaction)
                endTransaction(context, false);
            return list;
        }
        catch (Exception e) {
            Object[] args = { wheresql  };
            // Object[] args = { ((wheresql==null) ? "" : wheresql)  };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public List searchDocuments(String wheresql, boolean distinctbyname, int nb, int start, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        MonitorPlugin monitor  = getMonitorPlugin(context);
        try {
            StringBuffer sql;
            if (distinctbyname)
                sql = new StringBuffer("select distinct doc.web, doc.name, doc.language from XWikiDocument as doc");
            else
                sql = new StringBuffer("select distinct doc.web, doc.name from XWikiDocument as doc");

            if (wheresql==null)
                wheresql = "";
            wheresql.trim();
            if (!wheresql.equals("")) {
                if ((!wheresql.startsWith("where"))&&(!wheresql.startsWith(",")))
                    sql.append(" where ");
                else
                    sql.append(" ");

                sql.append(wheresql);
            }
            String ssql = sql.toString();

            // Start monitoring timer
            if (monitor!=null)
             monitor.startTimer("hibernate", ssql);

            checkHibernate(context);
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            Query query = session.createQuery(ssql);
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                Object[] result = (Object[]) it.next();

                XWikiDocument doc = new XWikiDocument((String)result[0], (String)result[1]);
                if (context.getWiki().getRightService().checkAccess("view", doc, context)==false)
                    continue;

                String name = doc.getFullName();
                if (distinctbyname) {
                    list.add(context.getWiki().getDocument(name, context));
                } else {
                    String language = (String) result[2];
                    if ((language==null)||(language.equals("")))
                        list.add(context.getWiki().getDocument(name, context));
                    else {
                        XWikiDocument doc2 = context.getWiki().getDocument(name, context);
                        list.add(doc2.getTranslatedDocument(language, context));
                    }
                }
            }
            if (bTransaction)
                endTransaction(context, false);
            return list;
        }
        catch (Exception e) {
            Object[] args = { wheresql  };
            // Object[] args = { ((wheresql==null) ? "" : wheresql)  };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    public Collection getConnections() {
        return connections.values();
    }

    public int getNbConnections() {
        return nbConnections;
    }

    public void setNbConnections(int nbConnections) {
        this.nbConnections = nbConnections;
    }


    public class ConnectionMonitor {
        private Exception exception;
        private Connection connection;
        private Date date;
        private URL url = null;

        public ConnectionMonitor(Connection connection, XWikiContext context) {
            this.setConnection(connection);

            try {
                setDate(new Date());
                setException(new XWikiException());
                XWikiRequest request = context.getRequest();
                if (request!=null)
                    setURL(XWiki.getRequestURL(context.getRequest()));
            } catch (Throwable e) {
            }

        }

        public Connection getConnection() {
            return connection;
        }

        public void setConnection(Connection connection) {
            this.connection = connection;
        }

        public Date getDate() {
            return date;
        }

        public void setDate(Date date) {
            this.date = date;
        }

        public Exception getException() {
            return exception;
        }

        public void setException(Exception exception) {
            this.exception = exception;
        }

        public URL getURL() {
            return url;
        }

        public void setURL(URL url) {
            this.url = url;
        }

    }
}

