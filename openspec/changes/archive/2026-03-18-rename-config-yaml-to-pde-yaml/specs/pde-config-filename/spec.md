## ADDED Requirements

### Requirement: PDE configuration file name
The system SHALL use `pde.yaml` as the only supported default configuration filename for PDE tooling configuration discovery.

#### Scenario: Default discovery resolves pde.yaml
- **WHEN** a tool performs configuration discovery in a working directory containing `pde.yaml`
- **THEN** it MUST load configuration from `pde.yaml`

### Requirement: Legacy filename is not accepted
The system MUST NOT treat `config.yaml` as a valid default configuration filename.

#### Scenario: Discovery with only legacy filename fails
- **WHEN** a tool performs configuration discovery in a working directory that contains `config.yaml` and does not contain `pde.yaml`
- **THEN** it MUST fail configuration discovery and report that `pde.yaml` is required

### Requirement: User-facing references use pde.yaml
All user-facing references to the default configuration filename SHALL use `pde.yaml`.

#### Scenario: Help and errors show canonical filename
- **WHEN** a tool emits help text, usage examples, or error messages about the default config filename
- **THEN** the message MUST reference `pde.yaml` and MUST NOT present `config.yaml` as supported
