/*
 * MongoSchemaLoader.java
 *
 * Tigase Jabber/XMPP Server - MongoDB support
 * Copyright (C) 2004-2017 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 */
package tigase.mongodb;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import tigase.db.*;
import tigase.db.util.DBSchemaLoader;
import tigase.db.util.SchemaLoader;
import tigase.util.ClassUtilBean;
import tigase.util.ReflectionHelper;
import tigase.util.ui.console.CommandlineParameter;
import tigase.xmpp.BareJID;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Created by andrzej on 05.05.2017.
 */
public class MongoSchemaLoader extends SchemaLoader<MongoSchemaLoader.Parameters> {

	private static final Logger log = Logger.getLogger(MongoSchemaLoader.class.getCanonicalName());

	private Parameters params;
	private MongoClient client;
	private MongoDataSource dataSource;

	@Override
	public Parameters createParameters() {
		return new Parameters();
	}

	@Override
	public void execute(SchemaLoader.Parameters params) {

	}

	@Override
	public void init(Parameters params) {
		this.params = params;
	}

	@Override
	public List<String> getSupportedTypes() {
		return Arrays.asList("mongodb");
	}

	@Override
	public String getDBUri() {
		return getDBUri(false);
	}

	private String getDBUri(boolean useRootCredentials) {
		StringBuilder sb = new StringBuilder();
		sb.append("mongodb://");
		if (useRootCredentials && params.getDbRootUser() != null) {
			if (params.getDbRootUser() != null) {
				sb.append(params.getDbRootUser());
				if (params.getDbPass() != null) {
					sb.append(":").append(params.getDbRootPass());
				}
				sb.append("@");
			}
			sb.append(params.getDbHostname()).append("/").append("admin");
		} else {
			if (params.getDbUser() != null) {
				sb.append(params.getDbUser());
				if (params.getDbPass() != null) {
					sb.append(":").append(params.getDbPass());
				}
				sb.append("@");
			}
			sb.append(params.getDbHostname()).append("/").append(params.getDbName());
		}

		String options = params.getDbOptions();
		if (options == null || options.isEmpty()) {
			if (params.isUseSSL()) {
				sb.append("?ssl=true");
			}
		} else {
			sb.append("?").append(options);
			if (params.isUseSSL()) {
				sb.append("&ssl=true");
			}
		}

		return sb.toString();
	}

	@Override
	public Result validateDBConnection() {
		try {
			String uri = getDBUri(true);
			client = new MongoClient(new MongoClientURI(uri));
			client.listDatabaseNames().iterator().hasNext();
			return Result.ok;
		} catch (MongoException ex) {
			log.log( Level.WARNING, ex.getMessage() );
			client = null;
			return Result.error;
		}
	}

	@Override
	public Result validateDBExists() {
		if (client == null) {
			log.log(Level.WARNING, "Connection not validated");
			return Result.error;
		}

		try {
			boolean exists = false;
			for (String name : client.listDatabaseNames()) {
				if (params.getDbName().equals(name)) {
					exists = true;
					break;
				}
			}
			MongoDatabase db;

			if (exists) {
				log.log( Level.INFO, "Exists OK" );
				db = client.getDatabase(params.getDbName());
			}
			else {
				log.log( Level.INFO, "Creating database..." );
				db = client.getDatabase(params.getDbName());
				log.log( Level.INFO, "OK" );
			}

			boolean createUser = client.getDatabase("admin")
					.getCollection("system.users")
					.find(Filters.and(Filters.eq("user", params.getDbUser()), Filters.eq("db", params.getDbName())))
					.first() == null;
			if (createUser) {
				Document result = db.runCommand(new Document().append("createUser", params.getDbUser())
									  .append("pwd", params.getDbPass())
									  .append("roles", Collections.singletonList(
											  new Document("role", "dbOwner").append("db", params.getDbName()))));
			}

			client.close();
			client = null;

			String uri = getDBUri();
			dataSource = new MongoDataSource();
			dataSource.initRepository(uri, new HashMap<>());

			return Result.ok;
		} catch (MongoException|DBInitException ex) {
			log.log(Level.WARNING, ex.getMessage());
			return Result.error;
		}
	}

	@Override
	public Result postInstallation() {
		return Result.ok;
	}

	@Override
	public Result printInfo() {
		if (dataSource == null) {
			log.log(Level.WARNING, "Connection not validated");
			return Result.error;
		}
		
		return super.printInfo();
	}

	@Override
	public Result addXmppAdminAccount() {
		if (dataSource == null) {
			log.log(Level.WARNING, "Connection not validated");
			return Result.error;
		}

		List<BareJID> jids = params.getAdmins();
		if ( jids.size() < 1 ){
			log.log( Level.WARNING, "Error: No admin users entered" );
			return Result.warning;
		}

		String pwd = params.getAdminPassword();
		if ( pwd == null ){
			log.log( Level.WARNING, "Error: No admin password entered" );
			return Result.warning;
		}

		ClassUtilBean.getInstance()
				.getAllClasses()
				.stream()
				.filter(clazz -> {
					Repository.SchemaId schema = clazz.getAnnotation(Repository.SchemaId.class);
					if (schema == null) {
						return false;
					}
					return Schema.SERVER_SCHEMA_ID.equals(schema.id());
				})
				.filter(clazz -> DataSourceAware.class.isAssignableFrom(clazz))
				.filter(clazz -> ReflectionHelper.classMatchesClassWithParameters(clazz, DataSourceAware.class,
																				  new Type[]{MongoDataSource.class}))
				.map(clazz -> {
					DataSourceAware<MongoDataSource> repository = null;
					try {
						repository = (DataSourceAware<MongoDataSource>) clazz.newInstance();
					} catch (Exception ex) {
						log.log(Level.WARNING, "Failed to create instance of " + clazz.getCanonicalName());
					}
					return repository;
				})
				.map(repo -> {
					if (repo == null) {
						return Result.error;
					} else {
						try {
							repo.setDataSource(dataSource);
							if (repo instanceof AuthRepository) {
								jids.forEach(jid -> {
									try {
										((AuthRepository) repo).addUser(jid, pwd);
									} catch (TigaseDBException ex) {
										log.log(Level.WARNING, ex.getMessage());
									}
								});
							}
							return Result.ok;
						} catch (Exception ex) {
							log.log(Level.WARNING, ex.getMessage());
							return Result.error;
						}
					}
				})
				.collect(Collectors.toSet());


		return Result.ok;
	}

	@Override
	public Result loadSchemaFile(String fileName) {
		return Result.error;
	}

	public Result destroyDataSource() {
		if (client == null) {
			log.log(Level.WARNING, "Connection not validated");
			return Result.error;
		}

		try {
			MongoDatabase db = client.getDatabase(params.getDbName());
			if (db != null) {
				db.runCommand(new Document().append("dropUser", params.getDbUser()));
			}
			client.dropDatabase(params.getDbName());
			return Result.ok;
		} catch (MongoException ex) {
			log.log(Level.WARNING, ex.getMessage());
			return Result.error;
		}
	}

	@Override
	public Result shutdown() {
		if (client == null) {
			log.log(Level.WARNING, "Connection not validated");
			return Result.error;
		}
		try {
			client.close();
			if (dataSource != null) {
				dataSource.beforeUnregister();
			}
			return Result.ok;
		} catch (MongoException ex) {
			log.log(Level.WARNING, ex.getMessage());
			return Result.error;
		}
	}

	@Override
	public Result loadSchema(String schemaId, String version) {
		if (dataSource == null) {
			log.log(Level.WARNING, "Connection not validated");
			return Result.error;
		}

		log.log(Level.INFO, "Loading schema " + schemaId + ", version: " + version);
		Set<Result> results = ClassUtilBean.getInstance()
				.getAllClasses()
				.stream()
				.filter(clazz -> {
					Repository.SchemaId schema = clazz.getAnnotation(Repository.SchemaId.class);
					if (schema == null) {
						return false;
					}
					return schemaId.equals(schema.id());
				})
				.filter(clazz -> DataSourceAware.class.isAssignableFrom(clazz))
				.filter(clazz -> ReflectionHelper.classMatchesClassWithParameters(clazz, DataSourceAware.class,
																				  new Type[]{MongoDataSource.class}))
				.map(clazz -> {
					DataSourceAware<MongoDataSource> repository = null;
					try {
						repository = (DataSourceAware<MongoDataSource>) clazz.newInstance();
					} catch (Exception ex) {
						log.log(Level.WARNING, "Failed to create instance of " + clazz.getCanonicalName());
					}
					return repository;
				})
				.map(repo -> {
					if (repo == null) {
						return Result.error;
					} else {
						try {
							repo.setDataSource(dataSource);
							if (repo instanceof RepositoryVersionAware) {
								((RepositoryVersionAware) repo).updateSchema();
							}
							return Result.ok;
						} catch (Exception ex) {
							log.log(Level.WARNING, ex.getMessage());
							return Result.error;
						}
					}
				})
				.collect(Collectors.toSet());

		return results.contains(Result.error)
			   ? Result.error
			   : (results.contains(Result.warning) ? Result.warning : Result.ok);
	}

	@Override
	public List<CommandlineParameter> getCommandlineParameters() {
		List<CommandlineParameter> options = getSetupOptions();

		options.add(new CommandlineParameter.Builder("L", DBSchemaLoader.PARAMETERS_ENUM.LOG_LEVEL.getName()).description(
				"Java Logger level during loading process")
								 .defaultValue(DBSchemaLoader.PARAMETERS_ENUM.LOG_LEVEL.getDefaultValue())
								 .build());
		options.add(new CommandlineParameter.Builder("J", DBSchemaLoader.PARAMETERS_ENUM.ADMIN_JID.getName()).description(
				"Comma separated list of administrator JID(s)").build());
		options.add(new CommandlineParameter.Builder("N", DBSchemaLoader.PARAMETERS_ENUM.ADMIN_JID_PASS.getName()).description(
				"Password that will be used for the entered JID(s) - one for all configured administrators")
								 .secret()
								 .build());
		return options;
	}

	public List<CommandlineParameter> getSetupOptions() {
		List<CommandlineParameter> options = new ArrayList<>();
		options.add(new CommandlineParameter.Builder("D", DBSchemaLoader.PARAMETERS_ENUM.DATABASE_NAME.getName()).description(
				"Name of the database that will be created and to which schema will be loaded")
							.defaultValue(DBSchemaLoader.PARAMETERS_ENUM.DATABASE_NAME.getDefaultValue())
							.required(true)
							.build());
		options.add(new CommandlineParameter.Builder("H", DBSchemaLoader.PARAMETERS_ENUM.DATABASE_HOSTNAME.getName()).description(
				"Address of the database instance")
							.defaultValue(DBSchemaLoader.PARAMETERS_ENUM.DATABASE_HOSTNAME.getDefaultValue())
							.required(true)
							.build());
		options.add(new CommandlineParameter.Builder("U", DBSchemaLoader.PARAMETERS_ENUM.TIGASE_USERNAME.getName()).description(
				"Name of the user that will be used")
							.defaultValue(DBSchemaLoader.PARAMETERS_ENUM.TIGASE_USERNAME.getDefaultValue())
							.build());
		options.add(new CommandlineParameter.Builder("P", DBSchemaLoader.PARAMETERS_ENUM.TIGASE_PASSWORD.getName()).description(
				"Password of the user that will be used")
							.defaultValue(DBSchemaLoader.PARAMETERS_ENUM.TIGASE_PASSWORD.getDefaultValue())
							.secret()
							.build());
		options.add(new CommandlineParameter.Builder("R", DBSchemaLoader.PARAMETERS_ENUM.ROOT_USERNAME.getName()).description(
				"Database root account username used to create tigase user and database")
							.build());
		options.add(new CommandlineParameter.Builder("A", DBSchemaLoader.PARAMETERS_ENUM.ROOT_PASSWORD.getName()).description(
				"Database root account password used to create tigase user and database")
							.secret()
							.build());
		options.add(new CommandlineParameter.Builder("S", DBSchemaLoader.PARAMETERS_ENUM.USE_SSL.getName()).description(
				"Enable SSL support for database connection")
							.requireArguments(false)
							.defaultValue(DBSchemaLoader.PARAMETERS_ENUM.USE_SSL.getDefaultValue())
							.type(Boolean.class)
							.build());
		options.add(new CommandlineParameter.Builder("O", DBSchemaLoader.PARAMETERS_ENUM.DATABASE_OPTIONS.getName())
							.description("Additional databse options query")
							.requireArguments(false)
							.build());
		return options;
	}
	
	public static class Parameters implements SchemaLoader.Parameters {

		private Level logLevel = Level.CONFIG;

		private String adminPassword;
		private List<BareJID> admins;
		private String dbName = null;
		private String dbHostname = null;
		private String dbUser = null;
		private String dbPass = null;
		private String dbRootUser = null;
		private String dbRootPass = null;
		private String dbOptions = null;
		private boolean useSSL = false;

		public String getDbRootUser() {
			return dbRootUser;
		}

		public String getDbRootPass() {
			return dbRootPass;
		}

		@Override
		public String getAdminPassword() {
			return adminPassword;
		}

		@Override
		public List<BareJID> getAdmins() {
			return admins == null ? Collections.emptyList() : admins;
		}

		public String getDbName() {
			return dbName;
		}

		public String getDbHostname() {
			return dbHostname;
		}

		public String getDbUser() {
			return dbUser;
		}

		public String getDbPass() {
			return dbPass;
		}

		public String getDbOptions() {
			return dbOptions;
		}

		public boolean isUseSSL() {
			return useSSL;
		}

		@Override
		public void parseUri(String uri) {
			MongoClientURI clientUri = new MongoClientURI(uri);

			dbUser = clientUri.getUsername();
			if (clientUri.getPassword() != null) {
				dbPass = new String(clientUri.getPassword());
			} else {
				dbPass = null;
			}

			dbHostname = clientUri.getHosts().stream().collect(Collectors.joining(","));
			dbName = clientUri.getDatabase();
			useSSL = clientUri.getOptions().isSslEnabled();
			int idx = uri.indexOf(dbName + "?");
			if (idx > 0) {
				dbOptions = uri.substring(idx + dbName.length() + 1);
			}
			if (dbOptions != null) {
				String tmp = "ssl=" + useSSL;
				idx = dbOptions.indexOf(tmp);
				if (idx == 0) {
					if (dbOptions.length() == tmp.length()) {
						dbOptions = "";
					} else {
						dbOptions = dbOptions.substring(tmp.length() + 1);
					}
				} else if (idx > 0) {
					dbOptions = dbOptions.replace("&" + tmp, "");
				}
			}
		}

		@Override
		public void setProperties(Properties props) {
			logLevel = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.LOG_LEVEL, val -> Level.parse(val));
			admins = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.ADMIN_JID, tmp -> Arrays.stream(tmp.split(","))
					.map(str -> BareJID.bareJIDInstanceNS(str))
					.collect(Collectors.toList()));
			adminPassword = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.ADMIN_JID_PASS);

			dbName = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.DATABASE_NAME);
			dbHostname = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.DATABASE_HOSTNAME);
			dbUser = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.TIGASE_USERNAME);
			dbPass = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.TIGASE_PASSWORD);
			useSSL = Optional.ofNullable(getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.USE_SSL, tmp -> Boolean.parseBoolean(tmp)))
					.orElse(false);
			dbOptions = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.DATABASE_OPTIONS);

			dbRootUser = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.ROOT_USERNAME);
			dbRootPass = getProperty(props, DBSchemaLoader.PARAMETERS_ENUM.ROOT_PASSWORD);
		}

		protected void init() {
		}

		private static String getProperty(Properties props, DBSchemaLoader.PARAMETERS_ENUM param) {
			return props.getProperty(param.getName(), param.getDefaultValue());
		}

		private static <T> T getProperty(Properties props, DBSchemaLoader.PARAMETERS_ENUM param, Function<String, T> converter) {
			String tmp = getProperty(props, param);
			if (tmp == null) {
				return null;
			}
			return converter.apply(tmp);
		}

		@Override
		public void setAdmins(List<BareJID> admins, String password) {
			this.admins = admins;
			this.adminPassword = password;
		}

		@Override
		public void setDbRootCredentials(String username, String password) {
			this.dbRootUser = username;
			this.dbRootPass = password;
		}

		@Override
		public String toString() {
			return "[" + Arrays.stream(this.getClass().getDeclaredFields()).map(field -> {
				String result = field.getName() + ": ";
				Object value;
				try {
					field.setAccessible(true);
					value = field.get(this);
				} catch (Exception ex) {
					value = "Error!";
				}
				return result + value;
			}).collect(Collectors.joining(", ")) + "]";
		}
	}
}
