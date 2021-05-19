import React from "react";
import _ from "lodash";
import {
  BaseDropdown,
  DropdownProps,
} from "components/designSystems/appsmith/Dropdown";
import { Field } from "redux-form";
import { DropdownOption } from "widgets/DropdownWidget";

interface DropdownFieldProps {
  name: string;
  className?: string;
  options: DropdownOption[];
  placeholder: string;
  width?: number | string;
  isSearchable?: boolean;
  isDisabled?: boolean;
}

function DropdownField(props: DropdownFieldProps & Partial<DropdownProps>) {
  return (
    <Field
      className={props.className}
      component={BaseDropdown}
      format={(value: string) => _.find(props.options, { value }) || ""}
      normalize={(option: { value: string }) => option.value}
      {...props}
      isDisabled={props.isDisabled}
      isSearchable={props.isSearchable}
      width={props.width}
    />
  );
}

export default DropdownField;
