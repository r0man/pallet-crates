(ns pallet.crate.package-builder
  "Build packages.

   For yum, this sets up an environment and provides methods for building
   rpms using mock and rpmbuild. First build a source rpm using
   `rpm-build-source-package`, the use `rpm-rebuild` to build for a specific
   target."
  (:require
   [pallet.parameter :as parameter]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.package.epel :as epel]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.user :as user]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))


;;; http://fedoraproject.org/wiki/PackageMaintainers/CreatingPackageHowTo
;;; http://wiki.centos.org/HowTos/SetupRpmBuildEnvironment

(defn- rpmbuild-dirs
  "A list of directories to create for building rpms with rpmbuild"
  [base]
  (concat
   [base]
   (map
    #(str base "/" %)
    ["BUILD" "BUILDROOT" "RPMS" "SPECS" "SOURCES" "SRPMS"])))

(defn yum-package-setup
  "Setup an environment for building yum packages with mock.
   Configuration of mock is required using yum-mock-config
   (or use one of the distribution supplied configuration files).

   You could link a configuration file as the default:

     (file/symbolic-link
       \"/etc/mock/epel-5-$(uname -i).cfg\" \"/etc/mock/default.cfg\")
"
  [session & {:keys [base-dir build-dir build-user packager-name packager-email]
              :or {build-user "build"
                   packager-name "Pallet package builder"
                   packager-email "none@nowhere.com"}}]
  (let [home (stevedore/script (user-home ~build-user))
        base-dir (or base-dir (str home "/rpmbuild"))]
    (->
     session
     (epel/add-epel)
     (package/package-manager :update)
     (package/package "rpm-build")
     (package/package "redhat-rpm-config")
     (package/package "mock")
     (user/user build-user :create-home true :groups "mock" )
     (directory/directories
      (rpmbuild-dirs base-dir)
      :owner build-user)
     (remote-file/remote-file
      (str "$HOME/.rpmmacros") ;; looked up by real user id
      :content (format
                "%%_topdir %s\n%%packager %s <%s>"
                base-dir packager-name packager-email)
      :owner build-user)
     (parameter/assoc-for-target ; record setup
      [:package-builder :base-dir] base-dir
      [:package-builder :build-user] build-user))))

(defn- format-mock-config
  "Format a configuration option in a mock configuration file"
  [[key value]]
  (format
   (if (and (string? value) (.contains value "\n"))
     "config_opts['%s'] = \"\"\"\n%s\n\"\"\"\n"
     "config_opts['%s'] = '%s'\n")
   (name key)
   value))

(defn yum-mock-config-content
  "Format a mock configuration file for the specified options.
   The config options include:
     :root              path element for the chroot directory
     :target_arch       target architecture (e.g. i386)
     :chroot_setup_cmd  command to setup chroot, e.g. \"install buildsys-build\"
     :dist              a distribution string, e.g. el5
     :yum.conf          string specifying yu,.conf for the chroot environment
"
  [session name {:as config}]
  (string/join (map format-mock-config config)))

(defn yum-mock-config-path
  "Path for a mock configuration file."
  [session name]
  (str "/etc/mock/" name ".cfg"))

(defn yum-mock-config
  "Write a mock configuration file with the given name and configuration
  options."
  [session name {:as config}]
  (remote-file/remote-file
   session
   (yum-mock-config-path session name)
   :content (yum-mock-config-content session name config)))

(defn source-path
  "Helper to return source path for a package."
  [session]
  (str
   (parameter/get-for-target session [:package-builder :base-dir]) "/SOURCES/"))

(defn srpm-path
  "Helper to return source path for a package."
  [session]
  (str
   (parameter/get-for-target session [:package-builder :base-dir]) "/SRPMS/"))

(defn spec-path
  "Helper to return rpm path for a package."
  [session]
  (str
   (parameter/get-for-target session [:package-builder :base-dir]) "/SPECS/"))

(defn rpm-path
  "Helper to return rpm path for a package."
  [session]
  (str
   (parameter/get-for-target session [:package-builder :base-dir]) "/RPMS/"))

(defn build-user
  "Helper to return the user for building packages."
  [session]
  (parameter/get-for-target session [:package-builder :build-user]))

(defn mock-init
  "Initialise a mock environment for a target architecture."
  [session & {:keys [target] :or {target "default"}}]
  (let [user (parameter/get-for-target session [:package-builder :build-user])]
    (exec-script/exec-checked-script    ; set up the mock chroot
     session
     "init mock chroot"
     (sudo -u ~user "/usr/bin/mock" -r ~target init))))

(defn rpm-build-source-package
  "Build a rpm source package."
  [session path]
  (let [user (parameter/get-for-target session [:package-builder :build-user])]
    (exec-script/exec-checked-script session
     (format "rpmbuild source package %s" path)
     (cd @(dirname ~path))
     (rpmbuild -bs --nodeps @(basename ~path)))))

(defn rpm-rebuild
  "Rebuild an rpm package based on a source package."
  [session src-rpm & {:keys [target resultdir] :or {target "default"}}]
  (let [user (parameter/get-for-target session [:package-builder :build-user])]
    (->
     session
     (exec-script/exec-checked-script
      (format "rpm rebuild %s" src-rpm)
      (sudo -u ~user "/usr/bin/mock"
            -r ~target
            --resultdir ~(or resultdir (rpm-path session))
            --rebuild ~src-rpm)))))

;; TODO - improve or remove this
(defn specfile
  "Trivial helper for specfiles."
  [& lines]
  (string/join "\n" lines))
