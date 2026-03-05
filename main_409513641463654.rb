
##########################
#ProgressBar
##########################
class ProgressBar
  VERSION = "0.9"

  def initialize (title, total, out = STDERR)
    @title = title
    @total = total
    @out = out
    @terminal_width = 80
    @bar_mark = "*"
    @current = 0
    @previous = 0
    @finished_p = false
    @start_time = Time.now
    @previous_time = @start_time
    @title_width = 14
    @format = "%-#{@title_width}s %3d%% %s %s"
    @format_arguments = [:title, :percentage, :bar, :stat]
    clear
    show
  end
  attr_reader :title
  attr_reader :current
  attr_reader :total
  attr_accessor :start_time
  attr_writer :bar_mark

  private
  def fmt_bar
    bar_width = do_percentage * @terminal_width / 100
    sprintf("|%s%s|",
      @bar_mark * bar_width,
      " " * (@terminal_width - bar_width))
  end

  def fmt_percentage
    do_percentage
  end

  def fmt_stat
    if @finished_p then elapsed else eta end
  end

  def fmt_stat_for_file_transfer
    if @finished_p then
      sprintf("%s %s %s", bytes, transfer_rate, elapsed)
    else
      sprintf("%s %s %s", bytes, transfer_rate, eta)
    end
  end

  def fmt_title
    @title[0,(@title_width - 1)] + ":"
  end

  def convert_bytes (bytes)
    if bytes < 1024
      sprintf("%6dB", bytes)
    elsif bytes < 1024 * 1000 # 1000kb
      sprintf("%5.1fKB", bytes.to_f / 1024)
    elsif bytes < 1024 * 1024 * 1000 # 1000mb
      sprintf("%5.1fMB", bytes.to_f / 1024 / 1024)
    else
      sprintf("%5.1fGB", bytes.to_f / 1024 / 1024 / 1024)
    end
  end

  def transfer_rate
    bytes_per_second = @current.to_f / (Time.now - @start_time)
    sprintf("%s/s", convert_bytes(bytes_per_second))
  end

  def bytes
    convert_bytes(@current)
  end

  def format_time (t)
    t = t.to_i
    sec = t % 60
    min = (t / 60) % 60
    hour = t / 3600
    sprintf("%02d:%02d:%02d", hour, min, sec);
  end

  # ETA stands for Estimated Time of Arrival.
  def eta
    if @current == 0
      "ETA: --:--:--"
    else
      elapsed = Time.now - @start_time
      eta = elapsed * @total / @current - elapsed;
      sprintf("ETA: %s", format_time(eta))
    end
  end

  def elapsed
    elapsed = Time.now - @start_time
    sprintf("Time: %s", format_time(elapsed))
  end

  def eol
    if @finished_p then "\n" else "\r" end
  end

  def do_percentage
    if @total.zero?
      100
    else
      @current * 100 / @total
    end
  end

  def get_width
    # FIXME: I don't know how portable it is.
    default_width = 80
    begin
      tiocgwinsz = 0x5413
      data = [0, 0, 0, 0].pack("SSSS")
      if @out.ioctl(tiocgwinsz, data) >= 0 then
        rows, cols, xpixels, ypixels = data.unpack("SSSS")
        if cols >= 0 then cols else default_width end
      else
        default_width
      end
    rescue Exception
      default_width
    end
  end

  def show
    arguments = @format_arguments.map {|method|
      method = sprintf("fmt_%s", method)
      send(method)
    }
    line = sprintf(@format, *arguments)

    width = get_width
    if line.length == width - 1
      @out.print(line + eol)
      @out.flush
    elsif line.length >= width
      @terminal_width = [@terminal_width - (line.length - width + 1), 0].max
      if @terminal_width == 0 then @out.print(line + eol) else show end
    else # line.length < width - 1
      @terminal_width += width - line.length + 1
      show
    end
    @previous_time = Time.now
  end

  def show_if_needed
    if @total.zero?
      cur_percentage = 100
      prev_percentage = 0
    else
      cur_percentage = (@current * 100 / @total).to_i
      prev_percentage = (@previous * 100 / @total).to_i
    end

    # Use "!=" instead of ">" to support negative changes
    if cur_percentage != prev_percentage ||
        Time.now - @previous_time >= 1 || @finished_p
      show
    end
  end

  public
  def clear
    @out.print "\r"
    @out.print(" " * (get_width - 1))
    @out.print "\r"
  end

  def finish
    @current = @total
    @finished_p = true
    show
  end

  def finished?
    @finished_p
  end

  def file_transfer_mode
    @format_arguments = [:title, :percentage, :bar, :stat_for_file_transfer]
  end

  def format= (format)
    @format = format
  end

  def format_arguments= (arguments)
    @format_arguments = arguments
  end

  def halt
    @finished_p = true
    show
  end

  def inc (step = 1)
    @current += step
    @current = @total if @current > @total
    show_if_needed
    @previous = @current
  end

  def set (count)
    if count < 0 || count > @total
      raise "invalid count: #{count} (total: #{@total})"
    end
    @current = count
    show_if_needed
    @previous = @current
  end

  def inspect
    "#<ProgressBar:#{@current}/#{@total}>"
  end
end

class ReversedProgressBar < ProgressBar
  def do_percentage
    100 - super
  end
end

###########################################################
#AutoPost
###########################################################
require 'digest/sha1'
require 'base64'
require 'rubygems'
gem 'soap4r'
require 'soap/wsdlDriver'
require 'yaml'
require 'pathname'
require 'find'
require 'fileutils'
require 'xmlsimple'
require 'time'
require 'logger'

#//set local script directory to find ap.properties file, also set a default CHUNKSIZE constant.
loc_dir = Pathname.new(Dir.pwd + '/conf/')
loc_dir.mkdir unless File.exist?(loc_dir)
XSD::Charset.encoding = 'UTF8'
CHUNKSIZE = 61440

@file_id_array = []
@file_md5_hash = {}
@timetrack = {}

#//Class to store the md5 hash and last used time.
class FileCache
	attr_reader :dataFileId, :lastUsed

	def initialize(dataFileId, lastUsed)
		@dataFileId = dataFileId
		@lastUsed = lastUsed
	end
end

#//Class to dump a ruby object (hash, array ,etc) to a gzipped file .. This allows persistence between autopost runs.
class ObjectFlush

  def self.store obj, file_name
    marshal_dmp = Marshal.dump(obj)
    file = File.new(file_name,'w')
    file = Zlib::GzipWriter.new(file)
    file.write marshal_dmp
    file.close
    return obj
  end

  def self.load file_name
      file = Zlib::GzipReader.open(file_name)
      obj = Marshal.load file.read
      file.close
      return obj
  end
end

#Patch Xml-Simple to preserve case on keynames when converted to Symbol.
class XmlSimple
  def merge(hash, key, value)
    if value.instance_of?(String)
      value = normalise_space(value) if @options['normalisespace'] == 2

      # do variable substitutions
      unless @_var_values.nil? || @_var_values.empty?
        value.gsub!(/\$\{(\w+)\}/) { |x| get_var($1) }
      end

      # look for variable definitions
      if @options.has_key?('varattr')
        varattr = @options['varattr']
        if hash.has_key?(varattr)
          set_var(hash[varattr], value)
        end
      end
    end

    #patch for converting keys to symbols
    #Patch by BW, removed .downcase on the key
    if @options.has_key?('keytosymbol')
      if @options['keytosymbol'] == true
        key = key.to_s.to_sym
      end
    end

    if hash.has_key?(key)
      if hash[key].instance_of?(Array)
        hash[key] << value
      else
        hash[key] = [ hash[key], value ]
      end
    elsif value.instance_of?(Array) # Handle anonymous arrays.
      hash[key] = [ value ]
    else
      if force_array?(key)
        hash[key] = [ value ]
      else
        hash[key] = value
      end
    end
    hash
  end
end

#add is_empty? method to the Dir class..I needed a way to determine if the watched directory is empty but with exclusions, for instance the
#["bds_envelope", ".", "..", "sent"] entries may exist. This added method to the Dir class returns true if there are no files other than
#the ones in the array, or if the bds_envelope.xml file is missing.
#

class Dir
  def Dir.is_empty?(path)

    return true if Dir.entries(path) == [".", "..", "bds_envelope.xml"]

    return true unless Dir.entries(path).include?("bds_envelope.xml")
    end
  end

#//Add a to_human method to the Numeric class so we can print human readable file sizes.
class Numeric
  def to_human
    units = %w{B KB MB GB TB}
    e = (Math.log(self)/Math.log(1024)).floor
    s = "%.3f" % (to_f / 1024**e)
    s.sub(/\.?0*$/, units[e])
  end
end

class String
      def mgsub(key_value_pairs=[].freeze)
        regexp_fragments = key_value_pairs.collect { |k,v| k }
        gsub(
Regexp.union(*regexp_fragments)) do |match|
          key_value_pairs.detect{|k,v| k =~ match}[1]
       end
     end
end

#Test if string is a valid email address.
def is_email?
  regx = /[A-Z0-9._%+-]+@[A-Z0-9.\_]+\.[A-Z]{2,4}/i
  if self.scan(regx) != []
   return true
  else
   return false
  end
end

#Test if username has a "domain\username" for AD logon.
def is_domain?
  regx = /^([a-z][a-z0-9.-]+)\\((?! +$)[a-z0-9 ]+)/i
  if self.scan(regx) != []
   return true
  else
   return false
  end
end

#Extract domain string into a 2 element array, output[0] = domainname , output[1] = username
def extract_domain(input)
  regx = /^([a-z][a-z0-9.-]+)\\((?! +$)[a-z0-9 ]+)/i
  return input.scan(regx).flatten![0] unless input.scan(regx) == []
end

#Extract the username from from a domain\username string
def extract_username_domain(input)
  regx = /^([a-z][a-z0-9.-]+)\\((?! +$)[a-z0-9 ]+)/i
  return input.scan(regx).flatten![1] unless input.scan(regx) == []
end

#//Perform signIn
def sign_in
  #//Check if sign_in needs to use a domain for authentication, if so then @username becomes an array with [0] as the domain, and [1] the username.
  #  If not domain login , then @username stays as a single string with the email address as the username. @email_address gets set to whatever is returned
  #  from BDS in the users Email Address attribute - this allows support for notification messages via AD authenticated users.

  if @username.send(:is_domain?)
    $LOG.debug("sign_in(): is_domain?: true")
    domain = extract_domain(@username)
    @username = extract_username_domain(@username)
    data = {
      :username => "#{@username}",
      :authenticationMethod => "PASSWORD",
      :domainName => "#{domain}",
      :base64PasswordHash => "#{@encPwd}",
      :encodingScheme => "SCH0",
      :channelType => "AutoPost"
    }
  else
    data = {
      :username => "#{@username}",
      :authenticationMethod => "PASSWORD",
      :base64PasswordHash => "#{@encPwd}",
      :encodingScheme => "SCH0",
      :channelType => "AutoPost"
    }
  end
  result = @driver.signIn(data)
  #//Check result of signIn
  if result.returnCode == "-2" or result.returnCode == "-1"

    raise "Invalid Username or Password.\n"
    puts "Delete the ap.properties file, and re-run the application again to reconfigure.\n"
    $LOG.info("Invalid Username or Password entered for #{@username} on server: #{@server_name}")
    exit
  end
  $LOG.info("#{@username} signed into #{@server_name}.")
  @session = result.sessionId
  @email_address = result.userVO.emailAddress
end


#//get local File name and size.....
def get_file(filename)
  #//Set local filename and file size variables
  @local_file = filename
  @local_file_size = File.size(@local_file)
  $LOG.debug("get_file(): Getting file: #{@local_file}, size: #{@local_file_size}")

  #//0 byte file uploads cause a null pointer exception in BDS , break if evals to true.
  if @local_file_size < 1
    puts "\n**Skipping 0 byte file: #{@local_file}**\n"
    $LOG.info"#{@local_file} was skipped due to 0 byte size."
    return nil
	else
    puts "\n"
    puts "New file detected! Uploading #{@local_file} now..."
    $LOG.info"The upload for #{@local_file} has started."
  end
end

#//Create upload object and begin initial upload, if the file is being accessed, exception handling will wait for 5 seconds before retrying, and it will time out and return to main loop after 10 retries..
def init_data_upload(local_file_size, session)
  if @file_md5_hash.has_value?(@file_regexp)
    @file_id = @file_md5_hash.index(@file_regexp)
    @file_id = @file_id.to_i
    @timetrack[@file_regexp] = FileCache.new(@file_id, Time.now)
    puts "\n"
    puts "**File uploaded previously** Reusing DataFileId #{@file_id}"
    puts "\n"
  else
    @init_upload = @driver.initiateDataFileUpload(:uploadFileSize => @local_file_size, :sessionId => @session)
    @file_id = @init_upload.dataFileId
    @file_id = @file_id.to_i
    
    #@file_md5_hash[@file_id] = FileCache.new(@file_regexp, Time.now) unless @file_regexp.empty?
    @file_md5_hash[@file_id] = @file_regexp unless @file_regexp.empty?
    @timetrack[@file_regexp] = FileCache.new(@file_id, Time.now) unless @file_regexp.empty?
    @file_regexp = ""
  
    #//Stream loop initiated here, exception handling if file cannot be read due to system operations, or lock.. It times out after 15 tries, and then breaks iteration.
    n = 0
    begin
      n += 1
      stream()
    rescue SystemCallError
      if n < 15
        $LOG.debug("init_data_upload(): Retried #{n} times to get #{@local_file}")
        $stderr.print "\nFile: #{@local_file} is locked, waiting for unlock..\n"
        $LOG.info"File: #{@local_file} locked, waits 5 seconds for unlock before retrying."
        sleep(5)
        retry
      else
        puts "Timeout on uploading #{@local_file} , the file could not be accessed.."
        $LOG.info"Timeout on upload of #{@local_file}, the file could not be accessed.."
        $LOG.debug"init_data_upload(): File: #{@local_file} skipped, retried #{n} times.."
        next
      end
    end
  end
end

  #// Scan the filename for an md5-hash
def regexp_scan(filename)
  @file_regexp = filename.scan(/[0-9a-f]{32}/)
end

#// Streaming method for updateDataFileUpload web service.. This is only called from the stream() method.
def upload_stream(pos, buffer)
  @driver_bin.updateDataFileUpload(:dataFileId => "#{@file_id}", :offset => @pos, :length => @chunk_size, :sourceIS => @buffer, :sessionId => @session)
  $LOG.debug("upload_stream(): dataFileId: #{@file_id}, offset: #{@pos}, length: #{@chunk_size}")
end

#// Stream the file into byte array to FDSAPIBIN using @chunk_size for the chunk size. The @chunk_size variable gets reset to value loaded from
#// whatever was found in ap.properties after a single transfer completes.
def stream()
  puts "File size = #{@local_file_size.to_human}\n"
  puts "Upload progress: "
  pbar = ProgressBar.new("Upload", @local_file_size)
  pbar.file_transfer_mode
  bytes = File.open(@local_file, "rb") do |io|
    @pos = io.tell
    if @local_file_size <= @chunk_size
      @chunk_size = @local_file_size
      @buffer = io.read(@chunk_size)
      upload_stream(@pos, @buffer)
      pbar.set(@pos)
      pbar.finish; break;
    else
      while @buffer = io.read(@chunk_size)
        upload_stream(@pos, @buffer)
        pbar.set(@pos)
        @pos = io.tell
        if @local_file_size-@pos < @chunk_size
          @chunk_size = @local_file_size-@pos
          if @chunk_size == 0; break; end;
        end
      end
      pbar.finish
      puts "\n"
    end
  end
end

#//Commit the upload after all chunks have been transfered.
def complete_send(fileId, session)
  complete = @driver.completeDataFileUpload(:dataFileId => fileId, :sessionId => session) #unless @file_regexp.empty?
  puts "Upload of #{@local_file} complete."
  $LOG.debug("complete_send(): dataFileId: #{fileId}")
end

#//Create an express delivery from upload object
#TODO: Needs CC and BCC support
def add_delivery(envelope)
  delivery = @driver.addExpressDelivery(envelope)
  puts "\n"
  puts "Delivery sent to: #{envelope[:recipientEmails][:to][0][:emails]}.."
  puts "\n"
  $LOG.info("Delivery sent to: #{envelope[:recipientEmails][:to][0][:emails]}..")
  @package_id = delivery.deliveryVO.packageId
end

#scan any string input for e-mail addresses, and output them as an array.
def extract_emails(input)
	regx = /[A-Z0-9._%+-]+@[A-Z0-9.\_]+\.[A-Z]{2,4}/i
  input.scan(regx).uniq
end


#Uses java System class to hide stdout user during password input.
def read_pwd(password)
  include Java
  include_class 'java.lang.System'
  password = System.console.readPassword.to_a
end

#Parses the bds_envelope.xml file for delivery meta-data.
def get_xml_env(file)
  file = open(file) {|f| f.read}

  #scan file in one pass to search and escape characters that XML requires.
  file = file.mgsub([[/&/i, '&amp;'], [/'/, '&apos;'], [/"/, '&quot;']])

  xml_env = XmlSimple.xml_in(file, {'ForceArray' => [:to], 'KeyToSymbol' => true, 'SuppressEmpty' => ""})
  xml_env[:dateExpires] = Time.parse(xml_env[:dateExpires]) unless xml_env[:dateExpires].nil?
  xml_env[:dateAvailable] = Time.parse(xml_env[:dateAvailable]) unless xml_env[:dateAvailable].nil?
  xml_env[:secureMessage] = {:useDefault => true} if xml_env[:secureMessage].nil?
  xml_env[:notificationMessage] = {:useDefault => true} if xml_env[:notificationMessage].nil?

  @additional_owners = xml_env[:additionalOwners][:emails].split(",") unless xml_env[:additionalOwners].nil?
  @additional_senders = xml_env[:additionalSenders][:emails].split(",") unless xml_env[:additionalSenders].nil?
  return xml_env
  $LOG.debug("get_xml_env(): #{xml_env.to_s}")
end

#This method only runs if the <additionalOwners> element is found in the bds_envelope.xml, this just modifies the package and adds
#the owners specified in bds_envelope.xml to the package just sent.
def edit_package_owners(pkg, session)
  hash = @additional_owners.inject([]){|result, email| result << {:key => "OWNER_EMAILS", :value => email}}

  data = {
   :packageId => "#{pkg}",
   :sessionId => "#{session}",
   :newPropValues => [{:key => "OWNER_EMAILS", :value => @email_address}]+hash,
   :publishAfterEdit => true
  }
  @driver.editPackage(data)
  @additional_owners = nil
end

#This method only runs if the <additionalSenders> element is found in the bds_envelope.xml, this just modifies the package and adds
#the senders specified in bds_envelope.xml to the package just sent.
def edit_package_senders(pkg, session)
  hash = @additional_senders.inject([]){|result, email| result << {:key => "SENDER_EMAILS", :value => email}}
  data = {
   :packageId => "#{pkg}",
   :sessionId => "#{session}",
   :newPropValues => hash,
   :publishAfterEdit => true
  }
  @driver.editPackage(data)
  @additional_senders = nil
end

#// The following code runs before all other code - it checks if the ap.properties exists, if it does - continue, if not.. prompt for
#// the configuration settings to populate a new ap.properties file and then continue.
begin
  #ap.properties not found, create a new ap.properties after prompting user for input.

  unless File.exists?('conf/ap.properties') == true
    puts "\n"
    puts "Note: The .\\conf\\ap.properties config file was not found, so you will be asked"
    puts "for the configuration properties only on the first execution of this script."
    puts "\n"
    puts ("**If the BDS instance you are connecting to is Active Directory integrated")
    puts ("please use \"Domain\\Username\" as the username.")
    puts "\n"
    print ("Enter your username:  ")
    STDOUT.flush
    @username = gets.chomp
    puts "\n"
    STDOUT.flush
    print "Enter your password:  "
    password = read_pwd(password).pack('c*')
    puts "\n"

    puts "Enter the BDS URL (ie: https://download.biscom.com):"
    servername = gets.chomp.downcase
    puts "\n"

    base64pwd = Base64.encode64(password).chomp
    @encPwd = Base64.encode64(Digest::SHA1.digest(password)).chomp

   print <<EOF
  **Important**

 Each subdirectory must have a bds_envelope.xml file in order to be processed.
 There is an example envelope in the AutoPost installation directory. Refer to
 the documentation for specifics on the envelope tags.

EOF

    puts "Enter the directory you would like to monitor (eg: C:\\Send), all subdirectories of this directory will be monitored:"
    dirinput = gets.chomp.downcase
    @dirwtmp = Pathname.new(dirinput)#.cleanpath.to_s
    @dirwatch = @dirwtmp
    puts "\n"

    #// dump configuration into a config file, password remains encrypted
    config = {"Username" => @username, "Password" => base64pwd, "Server" => servername, "Monitored_dir" => @dirwtmp.to_s, "Chunksize" => CHUNKSIZE, "Log_level" => "info", "Enable_md5_match" => false}
    @chunk_size = CHUNKSIZE
    @chunk_size_loaded = @chunk_size
    open(loc_dir + 'ap.properties', 'w') {|f| YAML.dump(config, f)}
    puts "\n"
    puts "Please re-run AutoPost.exe to begin to process the directory selected.."
    exit
    #//ap.properties was found, load the settings from the properties file.
  else
    config_obj = open(loc_dir + 'ap.properties') {|f| YAML.load(f)}
    @username = config_obj['Username']
    base64pwd = config_obj['Password']
    @password = Base64.decode64(base64pwd)
    @server_name = config_obj['Server']
    @dirwatch = config_obj['Monitored_dir']
    @chunk_size_loaded = config_obj['Chunksize']
    @chunk_size = @chunk_size_loaded
    @encPwd = Base64.encode64(Digest::SHA1.digest(@password)).chomp
    @log_level = config_obj['Log_level']
    @enable_md5_match = config_obj['Enable_md5_match']
  end
end

#//Enable logging, INFO mode - Adjust Logger::Formatter class and modify call method for a cleaner, short output.
#//logfile name is 'af.log' , it will grow to 1024000 bytes in 10 files before rolling over.
class Logger
  class Formatter
    remove_const :Format
    Format = "%s [%s] %s %s\n\n"
    def call(severity, time, progname, msg)
      Format % [severity, format_datetime(time), progname, msg]
    end
  end
end
log_dir = Pathname.new(Dir.pwd + '/log/')
log_dir.mkdir unless File.exist?(log_dir)

  if @log_level == "info"
    $LOG = Logger.new('log/apRolling.log', 10, 1024000)
    $LOG.datetime_format = "%m-%d-%Y %H:%M:%S"
    $LOG.level = Logger::INFO
  else @log_level == "debug"
    $LOG = Logger.new('log/apRolling.log', 10, 1024000)
    $LOG.datetime_format = "%m-%d-%Y %H:%M:%S"
    $LOG.level = Logger::DEBUG
  end

#----------------MAIN
#AutoPost
#----------------
@file_count = 0
@directory_count = 0
##// Retrieves the wsdl from the BDS server and creates all of the web service methods.

system_wsdl='/axis2/services/FDSAPI?wsdl'
system_wsdl_bin='/axis2/services/FDSAPIBIN?wsdl'
@wsdl_url="#{@server_name}#{system_wsdl}"
@wsdl_url_bin="#{@server_name}#{system_wsdl_bin}"
$VERBOSE = nil
factory = SOAP::WSDLDriverFactory.new(@wsdl_url)
@driver = factory.create_rpc_driver
@driver.endpoint_url = @wsdl_url.gsub(/.wsdl/, "")
@driver.use_default_namespace = true
@driver.options['protocol.http.ssl_config.verify_mode'] = OpenSSL::SSL::VERIFY_NONE

#//Initialize connection object to FDSAPIBin
factory_bin = SOAP::WSDLDriverFactory.new(@wsdl_url_bin)
@driver_bin = factory_bin.create_rpc_driver
@driver_bin.endpoint_url = @wsdl_url_bin.gsub(/.wsdl/, "")
@driver_bin.use_default_namespace = true
@driver_bin.options['protocol.http.ssl_config.verify_mode'] = OpenSSL::SSL::VERIFY_NONE
$VERBOSE = false

#Sign in to obtain session and other various user attributes..
sign_in()

#Check for the sent directory, create it if it doesnt exist...
#Dir.mkdir @dirwatch.to_s+"/sent" unless File.exists?(@dirwatch.to_s+"/sent") == true
#TODO: Implement optional sent folder, default on - when off files are deleted after being sent.
process_list = Hash.new {|h,k| h[k] = []}

#Iterate all subdirectories of the watched directory. Builds the process_list hash k=directory, v=filename
Dir["#{@dirwatch}/*/*"].each do |f|
  next unless File.file? f
  f = f.gsub(/\\/,'/')
  d, b = File.split f
  process_list[d] << b
end
if process_list.empty?
  puts "\n"
  puts "**All sub-directories found under: #{@dirwatch} were empty.**"
  puts "**No action taken.**"
  puts "\n"
end

#Check the md5 object stash for data and load it into the @file_md5_hash instance variable, this is to re-use data_file_ids of matching
#md5 hash's found via a regular expression in the filename. The data is stored into a ./data/db file which is a gzipped hash of the data_file_id
#with the matching regular expression.
if File.exists?("./data/db")
  @file_md5_hash = ObjectFlush.load("./data/db")
end

#Now that process_list is built with directories/filenames, process each envelope and upload file in the respective directory.
process_list.each_pair do |k,v|
  if Dir.is_empty?(k)
    $LOG.info("main(): Directory #{k} was found to be empty, or the bds_envelope.xml file was missing")
    puts "\n"
    puts "**Skipping directory: #{k}, either a bds_envelope.xml file could not be found - or the directory is empty.**"
    next
  end
  docs_vo = []
  envelope = {}
  @directory_count += 1
  temp_hash = {:trackingNumber => nil, :notificationEmails => [:emails => @email_address], :notifyOnEvents => ["DLV_ACCESS"], :newDocumentVOs => docs_vo, :sessionId => @session}
  Dir["#{k}/*"].each do |file|
    next if file.include?('bds_envelope.xml') or File.stat(file).directory?
    regexp_scan(file)
    @file_count += 1
    get_file(file)
    next if @local_file_size < 1
    @chunk_size = @chunk_size_loaded
    init_data_upload(@local_file_size, @session)
    complete_send(@file_id, @session) unless @file_md5_hash.has_value?(@file_regexp)
    docs_vo << [{:name => "#{File.basename(@local_file)}", :description => "", :dataFileId => @file_id, :directoryId => nil}]
  end
  docs_vo.flatten!
  envelope = get_xml_env(k+"/"+"bds_envelope.xml")
  envelope = temp_hash.merge!(envelope)
  puts "Additional package owners will be: #{@additional_owners}" unless @additional_owners.nil?
  add_delivery(envelope)
  
  #Clean up directory since delivery was sent.
  Dir["#{k}/*"].each do |file|
    next if file.include?('bds_envelope.xml') or File.stat(file).directory?
    #TODO Commented out during development to avoid having to copy files back into the send directories.
    #FileUtils.rm(file)
  end
  
  unless @additional_owners.nil?
    edit_package_owners(@package_id, @session)
  end
  unless @additional_senders.nil?
    edit_package_senders(@package_id, @session)
  end
end

@file_md5_hash.merge!(@timetrack)
ObjectFlush.store @file_md5_hash, './data/db' unless @file_md5_hash.empty?
puts "\n"
puts "Total directories processed: #{@directory_count}"
puts "Total files processed: #{@file_count}"

@timetrack.each do |k,v|
	puts k
	puts v.lastUsed
end

  