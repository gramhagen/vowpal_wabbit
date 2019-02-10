# -*- coding: utf-8 -*-
"""Vowpal Wabbit python setup module"""

import distutils.dir_util
import os
import platform
import sys
from codecs import open
from distutils.command.clean import clean as _clean
from distutils.sysconfig import get_config_var
from setuptools import setup, Extension, find_packages
from setuptools.command.build_ext import build_ext as _build_ext
from setuptools.command.install_lib import install_lib as _install_lib
from setuptools.command.sdist import sdist as _sdist
from shutil import copy, rmtree


BASE_DIR = os.path.abspath(os.path.dirname(__file__))


class CMakeExtension(Extension):
    def __init__(self, name):
        # don't invoke the original build_ext for this special extension
        Extension.__init__(self, name, sources=[])


class BuildPyLibVWBindingsModule(_build_ext):
    """Build pylibvw extension"""
    def run(self):
        for ext in self.extensions:
            self.build_cmake(ext)

    def build_cmake(self, ext):
        # Make build directory and move to it
        distutils.dir_util.mkpath(self.build_temp)
        os.chdir(str(self.build_temp))

        # Ensure lib output directory is made
        lib_output_dir = os.path.join(BASE_DIR, os.path.dirname(self.get_ext_fullpath(ext.name)))
        distutils.dir_util.mkpath(lib_output_dir)

        # Get python lib
        python_lib = os.path.join(get_config_var('LIBDIR'), get_config_var('INSTSONAME'))

        # Configure cmake build type
        config = 'Debug' if self.debug else 'Release'

        # Set cmake args
        cmake_args = [
            '-DCMAKE_LIBRARY_OUTPUT_DIRECTORY={}'.format(lib_output_dir),
            '-DCMAKE_MODULE_PATH={};${{CMAKE_MODULE_PATH}}'.format(BASE_DIR),
            '-DCMAKE_BUILD_TYPE={}'.format(config),
            '-DPY_VERSION=' + '{v[0]}.{v[1]}'.format(v=sys.version_info),
            '-DBUILD_PYTHON=ON',
            '-DWARNINGS=OFF',
            '-DPYTHON_INCLUDE_DIR={}'.format(get_config_var('INCLUDEPY')),
            '-DPYTHON_LIBRARY={}'.format(python_lib),
            '-DBoost_NO_SYSTEM_PATHS=ON',
            '-DBOOST_LIBRARYDIR={}'.format(get_config_var('LIBDIR')),
            '-DBOOST_INCLUDEDIR={}'.format(get_config_var('INCLUDEDIR')),
        ]

        # Cmake build args
        build_args = [
            '--config', config,
            '--', '-j8',
            # Set the pylibvw target
            "pylibvw"
        ]

        # Build pylibvw target
        cmake_directory = os.path.join(BASE_DIR, '..')
        self.spawn(['cmake', str(cmake_directory)] + cmake_args)
        if not self.dry_run:
            self.spawn(['cmake', '--build', '.'] + build_args)

        # Return to original directory
        os.chdir(str(BASE_DIR))


class Clean(_clean):
    """Clean up after building python package directories"""
    def run(self):
        rmtree(os.path.join(BASE_DIR, 'dist'), ignore_errors=True)
        rmtree(os.path.join(BASE_DIR, 'build'), ignore_errors=True)
        rmtree(os.path.join(BASE_DIR, 'vowpalwabbit.egg-info'), ignore_errors=True)
        _clean.run(self)


class Sdist(_sdist):
    """Create sdist for packaging"""
    def run(self):
        _sdist.run(self)


class InstallLib(_install_lib):
    """Install extension"""
    def build(self):
        _install_lib.build(self)
        if platform.system() == 'Windows':
            copy(os.path.join(BASE_DIR, 'bin', 'zlib.dll'), os.path.join(self.build_dir, 'zlib.dll'))


# Get the long description from the README file
with open(os.path.join(BASE_DIR, 'README.rst'), encoding='utf-8') as f:
    long_description = f.read()


# Get the current version for the python package from the version.txt file
config_path = os.path.join(BASE_DIR, '..', 'version.txt')
with open(config_path, encoding='utf-8') as f:
    version = f.readline().strip()


setup(
    name='vowpalwabbit',
    version=version,
    description='Vowpal Wabbit Python package',
    long_description=long_description,
    url='https://github.com/VowpalWabbit/vowpal_wabbit',
    author='Scott Graham',
    author_email='scott.d.graham@gmail.com',
    license='BSD 3-Clause License',
    classifiers=[
        'Development Status :: 4 - Beta',
        'Intended Audience :: Science/Research',
        'Topic :: Scientific/Engineering',
        'Topic :: Scientific/Engineering :: Information Analysis',
        'License :: OSI Approved :: BSD License',
        'Programming Language :: Python :: 2',
        'Programming Language :: Python :: 2.7',
        'Programming Language :: Python :: 3',
        'Programming Language :: Python :: 3.5',
        'Programming Language :: Python :: 3.6',
        'Programming Language :: Python :: 3.6',
    ],
    keywords='fast machine learning online classification regression',
    packages=find_packages(),
    platforms='any',
    zip_safe=False,
    include_package_data=True,
    ext_modules=[CMakeExtension('pylibvw')],
    cmdclass={
        'build_ext': BuildPyLibVWBindingsModule,
        'clean': Clean,
        'sdist': Sdist,
        'install_lib': InstallLib
    },
)
